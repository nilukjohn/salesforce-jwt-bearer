#!/usr/bin/env node
/**
 * GenerateJWT.js
 * --------------
 * Generates a signed JWT assertion for the Salesforce JWT Bearer Token flow.
 * No external packages required — uses Node.js built-in crypto module.
 *
 * Usage:
 *   node GenerateJWT.js --config jwt.properties
 *
 * The jwt.properties file must contain:
 *   consumer_key=<ECA_CONSUMER_KEY>
 *   username=<SF_USERNAME>
 *   audience=<MY_DOMAIN_URL>
 *   private_key=<path/to/private.key>
 *   expiry=180
 *
 * Notes:
 *   - The private key must be in PEM format.
 *   - Salesforce enforces a maximum JWT expiry of 3 minutes (180 seconds).
 *   - The script outputs the JWT assertion to stdout so it can be piped or captured.
 */

'use strict';

const fs     = require('fs');
const path   = require('path');
const crypto = require('crypto');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Base64url-encode a string or Buffer (RFC 7515).
 */
function base64url(input) {
  const buf = Buffer.isBuffer(input) ? input : Buffer.from(input, 'utf8');
  return buf.toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

/**
 * Parse a Java-style .properties file into a plain object.
 * Ignores blank lines and lines starting with # or !
 */
function parseProperties(filePath) {
  const resolved = path.resolve(filePath);
  if (!fs.existsSync(resolved)) {
    console.error(`ERROR: Properties file not found: ${filePath}`);
    process.exit(1);
  }
  const lines = fs.readFileSync(resolved, 'utf8').split(/\r?\n/);
  const props = {};
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) continue;
    const eqIndex = trimmed.indexOf('=');
    if (eqIndex < 0) continue;
    const key   = trimmed.substring(0, eqIndex).trim();
    const value = trimmed.substring(eqIndex + 1).trim();
    props[key] = value;
  }
  return props;
}

/**
 * Read PEM private key from disk.
 */
function loadPrivateKey(keyPath, basePath) {
  // Resolve relative to the properties file directory
  const resolved = path.isAbsolute(keyPath)
    ? keyPath
    : path.resolve(basePath, keyPath);
  if (!fs.existsSync(resolved)) {
    console.error(`ERROR: Private key file not found: ${resolved}`);
    process.exit(1);
  }
  return fs.readFileSync(resolved, 'utf8');
}

/**
 * Build and RS256-sign a JWT assertion for the Salesforce JWT Bearer flow.
 *
 * Claims:
 *   iss – Consumer Key of the External Client App (ECA)
 *   sub – Salesforce username of the integration user
 *   aud – My Domain URL for the org
 *   exp – Expiry timestamp (max 3 minutes from now)
 */
function buildJwt(consumerKey, username, audience, privateKeyPem, expirySeconds) {
  // Header
  const header = JSON.stringify({ alg: 'RS256', typ: 'JWT' });

  // Payload
  const now     = Math.floor(Date.now() / 1000);
  const payload = JSON.stringify({
    iss: consumerKey,
    sub: username,
    aud: audience,
    exp: now + expirySeconds,
  });

  // Unsigned token
  const unsignedToken = base64url(header) + '.' + base64url(payload);

  // Sign with RS256
  const signer    = crypto.createSign('RSA-SHA256');
  signer.update(unsignedToken);
  const signature = signer.sign(privateKeyPem);

  return unsignedToken + '.' + base64url(signature);
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function printHelp() {
  console.log(`
Usage:
  node GenerateJWT.js --config jwt.properties

Options:
  --config <path>   Path to jwt.properties file                     [required]
  --help            Show this help message

jwt.properties format:
  consumer_key=<ECA_CONSUMER_KEY>
  username=<SALESFORCE_USERNAME>
  audience=<MY_DOMAIN_URL>
  private_key=private.key
  expiry=180

Example:
  node GenerateJWT.js --config jwt.properties

  # Pipe into token exchange (PowerShell)
  $JWT = node GenerateJWT.js --config jwt.properties
  $response = Invoke-RestMethod -Method POST \`
      -Uri "https://[your-org].my.salesforce.com/services/oauth2/token" \`
      -Body "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$JWT" \`
      -ContentType "application/x-www-form-urlencoded"
`);
}

function main() {
  const argv = process.argv;
  let configPath = null;

  for (let i = 2; i < argv.length; i++) {
    switch (argv[i]) {
      case '--config':
        configPath = argv[++i];
        break;
      case '--help': case '-h':
        printHelp();
        process.exit(0);
        break;
      default:
        console.error(`ERROR: Unknown argument: ${argv[i]}`);
        printHelp();
        process.exit(1);
    }
  }

  if (!configPath) {
    console.error('ERROR: --config <path> is required.');
    printHelp();
    process.exit(1);
  }

  // Parse properties
  const props   = parseProperties(configPath);
  const basePath = path.dirname(path.resolve(configPath));

  const consumerKey = props.consumer_key;
  const username    = props.username;
  const audience    = props.audience;
  const keyPath     = props.private_key || 'private.key';
  const expiry      = parseInt(props.expiry || '180', 10);

  // Validate required properties
  if (!consumerKey) { console.error('ERROR: consumer_key is missing from properties file.'); process.exit(1); }
  if (!username)    { console.error('ERROR: username is missing from properties file.');     process.exit(1); }
  if (!audience)    { console.error('ERROR: audience is missing from properties file.');     process.exit(1); }

  // Validate expiry
  if (expiry > 180) {
    console.error('ERROR: Salesforce enforces a maximum JWT expiry of 180 seconds (3 minutes).');
    process.exit(1);
  }
  if (expiry < 1) {
    console.error('ERROR: expiry must be at least 1 second.');
    process.exit(1);
  }

  // Load key and generate JWT
  const privateKeyPem = loadPrivateKey(keyPath, basePath);
  const token = buildJwt(consumerKey, username, audience, privateKeyPem, expiry);

  process.stdout.write(token + '\n');
}

main();
