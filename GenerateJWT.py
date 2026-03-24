#!/usr/bin/env python3
"""
GenerateJWT.py
--------------
Generates a signed RS256 JWT assertion for the Salesforce JWT Bearer Token flow.

Requires the `cryptography` package:
    pip install cryptography

Run using config file (recommended):
    python GenerateJWT.py --config jwt.properties

Run (Dev org):
    python GenerateJWT.py \\
        --consumer-key YOUR_ECA_CONSUMER_KEY \\
        --username your.user@yourcompany.com \\
        --private-key server.key \\
        --org dev

Run (QA org):
    python GenerateJWT.py \\
        --consumer-key YOUR_ECA_CONSUMER_KEY \\
        --username your.user@yourcompany.com.qa \\
        --private-key server.key \\
        --org qa
"""

import argparse
import base64
import json
import math
import sys
import time
from pathlib import Path

# ── Dependency check ─────────────────────────────────────────────────────────

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.backends import default_backend
except ImportError:
    sys.exit(
        "ERROR: The 'cryptography' package is required.\n"
        "       Install it with:  pip install cryptography"
    )

# Salesforce token endpoint base URLs
PROD_AUD    = "https://login.salesforce.com"
SANDBOX_AUD = "https://test.salesforce.com"

# ── Helpers ──────────────────────────────────────────────────────────────────

def base64url(data: bytes | str) -> str:
    """Base64URL-encode bytes or a UTF-8 string, no padding."""
    if isinstance(data, str):
        data = data.encode("utf-8")
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def parse_properties(file_path: str) -> dict:
    """Parse a Java-style .properties file into a dict."""
    props = {}
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            sep = line.find("=")
            if sep == -1:
                continue
            key = line[:sep].strip()
            val = line[sep + 1:].strip()
            props[key] = val
    return props


def load_private_key(path: str):
    """Load an RS256 PEM private key (PKCS#8 or PKCS#1) from file."""
    if not Path(path).exists():
        sys.exit(f"ERROR: Private key file not found: {path}")
    with open(path, "rb") as f:
        pem_data = f.read()
    try:
        return serialization.load_pem_private_key(pem_data, password=None, backend=default_backend())
    except Exception as e:
        sys.exit(f"ERROR: Failed to load private key '{path}': {e}")

# ── Argument parsing ─────────────────────────────────────────────────────────

parser = argparse.ArgumentParser(
    prog="GenerateJWT.py",
    description="Generate a signed RS256 JWT for the Salesforce JWT Bearer Token flow.",
    formatter_class=argparse.RawTextHelpFormatter,
)
parser.add_argument("--config",       "-c", metavar="FILE",    help="Path to .properties file (e.g. jwt.properties)")
parser.add_argument("--consumer-key", "-k", metavar="KEY",     help="ECA Consumer Key (JWT issuer / client_id)")
parser.add_argument("--username",     "-u", metavar="USER",    help="Salesforce integration username (JWT subject)")
parser.add_argument("--private-key",  "-p", metavar="FILE",    help="Path to RS256 PEM private key file")
parser.add_argument("--org",          "-o", metavar="TYPE",    help="dev | qa | prod | sandbox  (default: prod)")
parser.add_argument("--expiry",       "-e", metavar="SECONDS", help="JWT lifetime in seconds, max 180  (default: 180)", type=int, default=None)
parser.add_argument("--verbose",      "-v", action="store_true", help="Print decoded claims to stderr")

args = parser.parse_args()

# ── Load config file ─────────────────────────────────────────────────────────

consumer_key     = args.consumer_key
username         = args.username
private_key_path = args.private_key
org              = args.org
expiry           = args.expiry if args.expiry is not None else 180
audience_override = None

if args.config:
    if not Path(args.config).exists():
        sys.exit(f"ERROR: Config file not found: {args.config}")
    props = parse_properties(args.config)
    if consumer_key     is None: consumer_key     = props.get("consumer_key")
    if username         is None: username         = props.get("username")
    if private_key_path is None: private_key_path = props.get("private_key")
    if org              is None: org              = props.get("org")
    if "audience" in props:      audience_override = props["audience"]
    if args.expiry is None and "expiry" in props:
        expiry = int(props["expiry"])

# ── Validate ─────────────────────────────────────────────────────────────────

if not consumer_key or not username or not private_key_path:
    parser.error("--consumer-key, --username, and --private-key are required.\n"
                 "       Run with --help for usage.")

if expiry > 180:
    sys.exit("ERROR: Salesforce enforces a maximum JWT expiry of 180 seconds (3 minutes).")
if expiry < 1:
    sys.exit("ERROR: Expiry must be at least 1 second.")

# ── Resolve audience ─────────────────────────────────────────────────────────

if audience_override and audience_override.strip():
    audience = audience_override.strip()
else:
    org_resolved = (org or "prod").lower()
    audience_map = {
        "dev":     PROD_AUD,
        "prod":    PROD_AUD,
        "qa":      SANDBOX_AUD,
        "sandbox": SANDBOX_AUD,
    }
    if org_resolved not in audience_map:
        sys.exit(f"ERROR: Unknown org type '{org_resolved}'. Use: dev, qa, prod, or sandbox.")
    audience = audience_map[org_resolved]

# ── Load private key ─────────────────────────────────────────────────────────

private_key = load_private_key(private_key_path)

# ── Build JWT ─────────────────────────────────────────────────────────────────

issued_at  = math.floor(time.time())
expires_at = issued_at + expiry

header_str  = json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":"))
payload_str = json.dumps(
    {"iss": consumer_key, "sub": username, "aud": audience, "exp": expires_at},
    separators=(",", ":"),
)

header  = base64url(header_str)
payload = base64url(payload_str)
signing_input = f"{header}.{payload}"

# ── Sign with RS256 ───────────────────────────────────────────────────────────

signature_bytes = private_key.sign(
    signing_input.encode("utf-8"),
    padding.PKCS1v15(),
    hashes.SHA256(),
)
signature = base64url(signature_bytes)

jwt_token = f"{signing_input}.{signature}"

# ── Verbose output (to stderr so JWT stays clean on stdout) ──────────────────

if args.verbose:
    org_label = org.upper() if org else "CONFIG"
    sys.stderr.write(f"[JWT] Org target   : {org_label} ({audience})\n")
    sys.stderr.write(f"[JWT] Issuer  (iss): {consumer_key}\n")
    sys.stderr.write(f"[JWT] Subject (sub): {username}\n")
    sys.stderr.write(f"[JWT] Audience(aud): {audience}\n")
    sys.stderr.write(f"[JWT] Issued at    : {issued_at}\n")
    sys.stderr.write(f"[JWT] Expires at   : {expires_at} (+{expiry}s)\n")
    sys.stderr.write(f"[JWT] Algorithm    : RS256\n")
    sys.stderr.write("\n")

# ── Output JWT ────────────────────────────────────────────────────────────────

print(jwt_token)
