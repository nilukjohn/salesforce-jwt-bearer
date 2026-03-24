#!/usr/bin/env node
/**
 * GenerateJWT.js
 * ---------------
 * Generates a signed RS256 JWT assertion for the Salesforce JWT Bearer Token flow.
 *
 * No external libraries required — uses Node.js built-in `crypto` and `fs` modules.
 *
 * Run using config file (recommended):
 *   node GenerateJWT.js --config jwt.properties
 *
 * Run (Dev org):
 *   node GenerateJWT.js \
 *       --consumer-key YOUR_ECA_CONSUMER_KEY \
 *       --username your.user@yourcompany.com \
 *       --private-key server.key \
 *       --org dev
 *
 * Run (QA org):
 *   node GenerateJWT.js \
 *       --consumer-key YOUR_ECA_CONSUMER_KEY \
 *       --username your.user@yourcompany.com.qa \
 *       --private-key server.key \
 *       --org qa
 */

'use strict';

const crypto = require('crypto');
const fs     = require('fs');
const path   = require('path');

// Salesforce token endpoint base URLs
const PROD_AUD    = 'https://login.salesforce.com';
const SANDBOX_AUD = 'https://test.salesforce.com';

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Base64URL-encode a string (UTF-8) or Buffer, no padding. */
function base64Url(input) {
    const buf = Buffer.isBuffer(input) ? input : Buffer.from(input, 'utf8');
    return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

/** Parse a simple Java-style .properties file into a plain object. */
function parseProperties(filePath) {
    const text = fs.readFileSync(filePath, 'utf8');
    const props = {};
    for (const line of text.split(/\r?\n/)) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) continue;
        const sep = trimmed.indexOf('=');
        if (sep === -1) continue;
        const key = trimmed.slice(0, sep).trim();
        const val = trimmed.slice(sep + 1).trim();
        props[key] = val;
    }
    return props;
}

/** Print usage and exit. */
function printHelp() {
    console.log('Usage: node GenerateJWT.js [options]');
    console.log('');
    console.log('Config file (recommended):');
    console.log('  --config,        -c  Path to .properties file (e.g. jwt.properties)');
    console.log('');
    console.log('Required (if not using config file):');
    console.log('  --consumer-key,  -k  ECA Consumer Key (JWT issuer / client_id)');
    console.log('  --username,      -u  Salesforce integration username (JWT subject)');
    console.log('  --private-key,   -p  Path to RS256 PEM private key file');
    console.log('');
    console.log('Optional:');
    console.log('  --org,           -o  dev | qa | prod | sandbox  (default: prod)');
    console.log('                         dev  → login.salesforce.com');
    console.log('                         qa   → test.salesforce.com');
    console.log('  --expiry,        -e  Lifetime in seconds, max 180  (default: 180)');
    console.log('  --verbose,       -v  Print decoded claims to stderr');
    console.log('  --help,          -h  Show this help message');
    console.log('');
    console.log('Examples:');
    console.log('  node GenerateJWT.js --config jwt.properties');
    console.log('  node GenerateJWT.js --config jwt.properties --verbose');
    console.log('  node GenerateJWT.js --consumer-key YOUR_KEY... --username user@yourcompany.com --private-key server.key --org dev');
    console.log('  node GenerateJWT.js -k YOUR_KEY... -u user@yourcompany.com.qa -p server.key -o qa --verbose');
    process.exit(0);
}

// ── Argument parsing ─────────────────────────────────────────────────────────

const args = process.argv.slice(2);
let consumerKey     = null;
let username        = null;
let privateKeyPath  = null;
let org             = null;
let audienceOverride = null;
let expiry          = 180;
let verbose         = false;
let configFile      = null;

for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
        case '--config':       case '-c': configFile      = args[++i]; break;
        case '--consumer-key': case '-k': consumerKey     = args[++i]; break;
        case '--username':     case '-u': username        = args[++i]; break;
        case '--private-key':  case '-p': privateKeyPath  = args[++i]; break;
        case '--org':          case '-o': org             = args[++i]; break;
        case '--expiry':       case '-e': expiry          = parseInt(args[++i], 10); break;
        case '--verbose':      case '-v': verbose         = true; break;
        case '--help':         case '-h': printHelp(); break;
        default:
            console.error(`ERROR: Unknown argument: ${args[i]}`);
            process.exit(1);
    }
}

// ── Load config file ─────────────────────────────────────────────────────────

if (configFile) {
    if (!fs.existsSync(configFile)) {
        console.error(`ERROR: Config file not found: ${configFile}`);
        process.exit(1);
    }
    const props = parseProperties(configFile);
    if (consumerKey    === null && props.consumer_key)  consumerKey    = props.consumer_key;
    if (username       === null && props.username)      username       = props.username;
    if (privateKeyPath === null && props.private_key)   privateKeyPath = props.private_key;
    if (org            === null && props.org)           org            = props.org;
    if (props.audience)                                 audienceOverride = props.audience;
    if (props.expiry)                                   expiry         = parseInt(props.expiry, 10);
}

// ── Validate ─────────────────────────────────────────────────────────────────

if (!consumerKey || !username || !privateKeyPath) {
    console.error('ERROR: --consumer-key, --username, and --private-key are required.');
    console.error('       Run with --help for usage.');
    process.exit(1);
}
if (expiry > 180) {
    console.error('ERROR: Salesforce enforces a maximum JWT expiry of 180 seconds (3 minutes).');
    process.exit(1);
}
if (expiry < 1) {
    console.error('ERROR: Expiry must be at least 1 second.');
    process.exit(1);
}

// ── Resolve audience ─────────────────────────────────────────────────────────

let audience;
if (audienceOverride && audienceOverride.trim()) {
    audience = audienceOverride.trim();
} else {
    const orgResolved = (org || 'prod').toLowerCase();
    switch (orgResolved) {
        case 'dev':
        case 'prod':    audience = PROD_AUD;    break;
        case 'qa':
        case 'sandbox': audience = SANDBOX_AUD; break;
        default:
            console.error(`ERROR: Unknown org type '${orgResolved}'. Use: dev, qa, prod, or sandbox.`);
            process.exit(1);
    }
}

// ── Load private key ─────────────────────────────────────────────────────────

if (!fs.existsSync(privateKeyPath)) {
    console.error(`ERROR: Private key file not found: ${privateKeyPath}`);
    process.exit(1);
}
const privatePem = fs.readFileSync(privateKeyPath, 'utf8');

// ── Build JWT ─────────────────────────────────────────────────────────────────

const issuedAt  = Math.floor(Date.now() / 1000);
const expiresAt = issuedAt + expiry;

const headerStr  = JSON.stringify({ alg: 'RS256', typ: 'JWT' });
const payloadStr = JSON.stringify({ iss: consumerKey, sub: username, aud: audience, exp: expiresAt });

const header  = base64Url(headerStr);
const payload = base64Url(payloadStr);
const signingInput = `${header}.${payload}`;

// ── Sign with RS256 ───────────────────────────────────────────────────────────

const signer = crypto.createSign('RSA-SHA256');
signer.update(signingInput, 'utf8');
const signatureBytes = signer.sign(privatePem);
const signature = base64Url(signatureBytes);

const jwt = `${signingInput}.${signature}`;

// ── Verbose output (to stderr so JWT stays clean on stdout) ──────────────────

if (verbose) {
    const orgLabel = org ? org.toUpperCase() : 'CONFIG';
    process.stderr.write(`[JWT] Org target   : ${orgLabel} (${audience})\n`);
    process.stderr.write(`[JWT] Issuer  (iss): ${consumerKey}\n`);
    process.stderr.write(`[JWT] Subject (sub): ${username}\n`);
    process.stderr.write(`[JWT] Audience(aud): ${audience}\n`);
    process.stderr.write(`[JWT] Issued at    : ${issuedAt}\n`);
    process.stderr.write(`[JWT] Expires at   : ${expiresAt} (+${expiry}s)\n`);
    process.stderr.write(`[JWT] Algorithm    : RS256\n`);
    process.stderr.write('\n');
}

// ── Output JWT ────────────────────────────────────────────────────────────────

process.stdout.write(jwt + '\n');
