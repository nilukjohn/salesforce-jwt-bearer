#!/usr/bin/env python3
"""
GenerateJWT.py
--------------
Generates a signed JWT assertion for the Salesforce JWT Bearer Token flow.
Uses the `cryptography` library for RS256 signing — no PyJWT needed.

Usage:
    python GenerateJWT.py --config jwt.properties

Requirements:
    pip install cryptography

The jwt.properties file must contain:
    consumer_key=<ECA_CONSUMER_KEY>
    username=<SF_USERNAME>
    audience=<MY_DOMAIN_URL>
    private_key=<path/to/private.key>
    expiry=180

Notes:
    - The private key must be in PEM format.
    - Salesforce enforces a maximum JWT expiry of 3 minutes (180 seconds).
    - The script outputs the JWT assertion to stdout so it can be piped or captured.
"""

import argparse
import base64
import json
import os
import sys
import time
from pathlib import Path

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
except ImportError:
    sys.exit(
        "ERROR: cryptography is not installed. Run:  pip install cryptography"
    )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def base64url_encode(data: bytes) -> str:
    """Base64url-encode bytes (RFC 7515 — no padding)."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def parse_properties(file_path: str) -> dict:
    """
    Parse a Java-style .properties file into a dict.
    Ignores blank lines and lines starting with # or !
    """
    props = {}
    path_obj = Path(file_path)
    if not path_obj.exists():
        sys.exit(f"ERROR: Properties file not found: {file_path}")
    for line in path_obj.read_text().splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        eq_index = stripped.find("=")
        if eq_index < 0:
            continue
        key = stripped[:eq_index].strip()
        value = stripped[eq_index + 1 :].strip()
        props[key] = value
    return props


def load_private_key(key_path: str, base_path: str):
    """Read and return the PEM private key from disk."""
    resolved = (
        Path(key_path)
        if os.path.isabs(key_path)
        else Path(base_path) / key_path
    )
    if not resolved.exists():
        sys.exit(f"ERROR: Private key file not found: {resolved}")
    if not resolved.is_file():
        sys.exit(f"ERROR: Path is not a file: {resolved}")
    pem_data = resolved.read_bytes()
    return serialization.load_pem_private_key(pem_data, password=None)


def build_jwt(
    consumer_key: str,
    username: str,
    audience: str,
    private_key,
    expiry_seconds: int = 180,
) -> str:
    """
    Construct and RS256-sign a JWT assertion for the Salesforce JWT Bearer flow.

    Claims:
        iss  - Consumer Key of the External Client App (ECA)
        sub  - Salesforce username of the integration user
        aud  - My Domain URL for the org
        exp  - Expiry timestamp (max 3 minutes from now)

    Returns:
        Signed JWT string.
    """
    # Header
    header = json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":"))

    # Payload
    now = int(time.time())
    payload = json.dumps(
        {
            "iss": consumer_key,
            "sub": username,
            "aud": audience,
            "exp": now + expiry_seconds,
        },
        separators=(",", ":"),
    )

    # Unsigned token
    unsigned_token = base64url_encode(header.encode()) + "." + base64url_encode(payload.encode())

    # Sign with RS256
    signature = private_key.sign(
        unsigned_token.encode("utf-8"),
        padding.PKCS1v15(),
        hashes.SHA256(),
    )

    return unsigned_token + "." + base64url_encode(signature)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate a Salesforce JWT Bearer assertion (RS256-signed).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python GenerateJWT.py --config jwt.properties

  # Pipe into token exchange (PowerShell)
  $JWT = python GenerateJWT.py --config jwt.properties
  $response = Invoke-RestMethod -Method POST `
      -Uri "https://[your-org].my.salesforce.com/services/oauth2/token" `
      -Body "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$JWT" `
      -ContentType "application/x-www-form-urlencoded"
        """,
    )

    parser.add_argument(
        "--config", "-c",
        required=True,
        metavar="PATH",
        help="Path to jwt.properties configuration file.",
    )

    args = parser.parse_args()

    # Parse properties file
    props = parse_properties(args.config)
    base_path = str(Path(args.config).resolve().parent)

    consumer_key = props.get("consumer_key")
    username = props.get("username")
    audience = props.get("audience")
    key_path = props.get("private_key", "private.key")
    expiry = int(props.get("expiry", "180"))

    # Validate required properties
    if not consumer_key:
        sys.exit("ERROR: consumer_key is missing from properties file.")
    if not username:
        sys.exit("ERROR: username is missing from properties file.")
    if not audience:
        sys.exit("ERROR: audience is missing from properties file.")

    # Validate expiry
    if expiry > 180:
        sys.exit(
            "ERROR: Salesforce enforces a maximum JWT expiry of 180 seconds (3 minutes)."
        )
    if expiry < 1:
        sys.exit("ERROR: expiry must be at least 1 second.")

    # Load key and generate JWT
    private_key = load_private_key(key_path, base_path)
    token = build_jwt(consumer_key, username, audience, private_key, expiry)

    print(token)


if __name__ == "__main__":
    main()
