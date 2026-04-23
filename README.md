# JWT Bearer Token Generator
### Salesforce OAuth 2.0 — Client Instructions

---

## Help Docs — Get the Files from GitHub

All scripts are hosted publicly on GitHub:

**GitHub Repo:** [github.com/nilukjohn/salesforce-jwt-bearer](https://github.com/nilukjohn/salesforce-jwt-bearer)

Click the green **Code** button → **Download ZIP** → extract to a local folder.

Or clone with Git:
```powershell
git clone https://github.com/nilukjohn/salesforce-jwt-bearer.git
```

After downloading, copy your **private.key** (see Step 1) file into the same folder as the scripts.

---

## Prerequisites

Choose **one** runtime — all three use the same `jwt.properties` config file:

| Runtime | Requirement | Verify |
|---|---|---|
| **Java** | JDK installed | `javac -version` |
| **Node.js** | Node.js installed | `node --version` |
| **Python** | Python 3 + `cryptography` package | `python --version` |

All files must be in the same folder:
  - `GenerateJWT.java` / `GenerateJWT.js` / `GenerateJWT.py`
  - `jwt.properties`
  - Your private key `.key` file

---

## Step 1 — Client Generates Keypair (`private.key` + `public.crt`) via OpenSSL

> **Fallback method:** Salesforce Certificate and Key Management → export JKS → convert to PEM → deliver private key via secure channel.

```bash
# Step 1: Generate password-protected RSA key
openssl genrsa -des3 -passout pass:x -out private.pass.key 2048

# Step 2: Strip passphrase
openssl rsa -passin pass:x -in private.pass.key -out private.key

# Step 3: Remove intermediate key
rm private.pass.key

# Step 4: Create Certificate Signing Request
openssl req -new -key private.key -out private.csr

# Step 5: Generate self-signed certificate (365 days: This Cert expiration length can be updated)
openssl x509 -req -sha256 -days 365 -in private.csr -signkey private.key -out public.crt
```

Client sends only `public.crt` to SF Admin → **`private.key` never leaves the client**.

---

## Step 2 — Configure `jwt.properties`

Update the `jwt.properties` file with the values provided by the SF Admin:

| Property | Description | Where to get it |
|---|---|---|
| `consumer_key` | ECA Consumer Key (JWT `iss`) | SF Admin → Setup → External Client Apps |
| `username` | Salesforce integration username (JWT `sub`) | SF Admin → Setup → Users |
| `audience` | My Domain URL (JWT `aud`) | SF Admin → Setup → My Domain |
| `private_key` | Path to your private key file | `private.key` (in the same folder) |
| `expiry` | Token lifetime in seconds (max 180) | `180` (recommended) |

### Audience (`aud`) — My Domain URL

Set `audience` to your org's **My Domain URL**, not the generic Salesforce endpoints. This is the recommended approach per Salesforce documentation.

| Org Type | Format | Example |
|---|---|---|
| **Sandbox** | `https://[MyDomain]--[SandboxName].sandbox.my.salesforce.com` | `https://mycompany--qa.sandbox.my.salesforce.com` |
| **Production** | `https://[MyDomain].my.salesforce.com` | `https://mycompany.my.salesforce.com` |

> Your My Domain URL can be found in Salesforce Setup → **My Domain**.

---

## Step 3 — Generate JWT & Exchange for Access Token

Three runtime options are available — all use the same `jwt.properties` file. Run these commands in PowerShell/Bash:

Open PowerShell and navigate to this folder:

```powershell
cd C:\path\to\your\JWT\folder
```

---

### Option A — Java

**Compile (run once, or after changes to `GenerateJWT.java`)**
```powershell
javac GenerateJWT.java
```

**Generate JWT**
```powershell
java GenerateJWT --config jwt.properties
```

**With verbose output**
```powershell
java GenerateJWT --config jwt.properties --verbose
```

**Capture into variable + exchange for access token**
```powershell
$JWT = java GenerateJWT --config jwt.properties

$response = Invoke-RestMethod `
    -Method POST `
    -Uri "https://[your-org].my.salesforce.com/services/oauth2/token" `
    -Body "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$JWT" `
    -ContentType "application/x-www-form-urlencoded"

$accessToken = $response.access_token
$instanceUrl = $response.instance_url
```

---

### Option B — Node.js

> No compile step required. Uses Node.js built-in `crypto` module — no external packages needed.

**Generate JWT**
```powershell
node GenerateJWT.js --config jwt.properties
```

**With verbose output**
```powershell
node GenerateJWT.js --config jwt.properties --verbose
```

**Capture into variable + exchange for access token**
```powershell
$JWT = node GenerateJWT.js --config jwt.properties

$response = Invoke-RestMethod `
    -Method POST `
    -Uri "https://[your-org].my.salesforce.com/services/oauth2/token" `
    -Body "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$JWT" `
    -ContentType "application/x-www-form-urlencoded"

$accessToken = $response.access_token
$instanceUrl = $response.instance_url
```

---

### Option C — Python

**Install dependency (run once)**
```powershell
pip install cryptography
```

**Generate JWT**
```powershell
python GenerateJWT.py --config jwt.properties
```

**With verbose output**
```powershell
python GenerateJWT.py --config jwt.properties --verbose
```

**Capture into variable + exchange for access token**
```powershell
$JWT = python GenerateJWT.py --config jwt.properties

$response = Invoke-RestMethod `
    -Method POST `
    -Uri "https://[your-org].my.salesforce.com/services/oauth2/token" `
    -Body "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$JWT" `
    -ContentType "application/x-www-form-urlencoded"

$accessToken = $response.access_token
$instanceUrl = $response.instance_url
```

---

## Alternative: Generate JWT via jwt.io

> Use this method for one-off tests or if you don't have a runtime installed.

If you prefer a browser-based approach without running any code, you can use [jwt.io](https://jwt.io) to manually build and sign the JWT.

### Steps

1. Go to [https://jwt.io](https://jwt.io) and click the **JWT Encoder** tab
2. Set the algorithm to **RS256**
3. Fill in the **Payload** with your values:

```json
{
  "iss": "<consumer_key from jwt.properties>",
  "sub": "<username from jwt.properties>",
  "aud": "<audience My Domain URL from jwt.properties>",
  "exp": 1773862391
}
```

4. Calculate the `exp` value — open your browser console and run:

```javascript
Math.floor(Date.now() / 1000) + 180
```

Paste the result as the `exp` value in the payload.

5. Under **Sign JWT**, set **Private Key Format** to `PEM` and paste the contents of your `private.key` file
6. Confirm all three green indicators are shown: **Valid header**, **Valid payload**, **Valid private key**
7. Copy the **Encoded JWT** from the right panel — this is your signed JWT assertion

> ⚠️ jwt.io is a public tool. Do not paste production private keys into it. Use only for testing and validation purposes.

---

## JWT Claims

| Claim | Field | Description |
|---|---|---|
| `iss` | Issuer | ECA Consumer Key from `jwt.properties` |
| `sub` | Subject | Salesforce integration username from `jwt.properties` |
| `aud` | Audience | Your org's My Domain URL from `jwt.properties` |
| `exp` | Expiry | `current Unix time (seconds) + 180` |

The `exp` is equivalent to `Math.floor(Date.now() / 1000) + 180` — the token is valid for **3 minutes** from the moment the script is run.

---

## Sample Token Response

After exchanging the JWT assertion with Salesforce, you will receive a response like this:

```json
{
    "access_token": "<your-access-token>",
    "scope": "web api",
    "instance_url": "https://[your-org].my.salesforce.com",
    "id": "https://[login-url]/id/[org-id]/[user-id]",
    "token_type": "Bearer"
}
```

| Field | Description |
|---|---|
| `access_token` | The token to use in all Salesforce API requests |
| `instance_url` | The base URL for all API calls — always use this, not a hardcoded domain |
| `token_type` | Always `Bearer` — used as the Authorization header prefix |
| `scope` | Permissions granted to this token |

---

## Using the Access Token for Salesforce API Requests

Pass the `access_token` as a `Bearer` token in the `Authorization` header of every Salesforce API request.

### PowerShell — Full flow (token exchange + API call)

```powershell
$JWT = java GenerateJWT --config jwt.properties

# Exchange JWT for access token
$response = Invoke-RestMethod `
    -Method POST `
    -Uri "https://[your-org].my.salesforce.com/services/oauth2/token" `
    -Body "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$JWT" `
    -ContentType "application/x-www-form-urlencoded"

$accessToken = $response.access_token
$instanceUrl = $response.instance_url

# Use access token to query Salesforce
Invoke-RestMethod `
    -Method GET `
    -Uri "$instanceUrl/services/data/v59.0/query?q=SELECT+Id,Name+FROM+Account+LIMIT+5" `
    -Headers @{ Authorization = "Bearer $accessToken" }
```

### curl equivalent

```bash
# Exchange JWT for access token
TOKEN=$(curl -s -X POST \
  "https://[your-org].my.salesforce.com/services/oauth2/token" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$(java GenerateJWT --config jwt.properties)" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Use access token in API request
curl -H "Authorization: Bearer $TOKEN" \
  "$INSTANCE_URL/services/data/v59.0/query?q=SELECT+Id,Name+FROM+Account+LIMIT+5"
```

### Key rules for using the access token

- Always prefix the token value with `Bearer ` in the `Authorization` header
- The token expires after **3 minutes** — regenerate using the script when needed
- Always use `instance_url` from the token response as the base URL for API calls — never hardcode the org domain

---

## Salesforce Reference

- [OAuth 2.0 JWT Bearer Flow for Server-to-Server Integration](https://help.salesforce.com/s/articleView?id=xcloud.remoteaccess_oauth_jwt_flow.htm&language=en_US&type=5)
- [Configure a JWT Bearer Flow](https://help.salesforce.com/s/articleView?id=xcloud.configure_oauth_jwt_flow_external_client_apps.htm&language=en_US&type=5)
- [Create a Private Key and Self-Signed Certificate](https://developer.salesforce.com/docs/atlas.en-us.sfdx_dev.meta/sfdx_dev/sfdx_dev_auth_key_and_cert.htm)

---

## Notes

- All three scripts (`GenerateJWT.java`, `GenerateJWT.js`, `GenerateJWT.py`) share the same interface and `jwt.properties` config
- **Java**: No external libraries — uses built-in `java.security` package only. Recompile (`javac GenerateJWT.java`) only needed when the `.java` file changes
- **Node.js**: No external packages — uses built-in `crypto` module. No compile step required
- **Python**: Requires the `cryptography` package (`pip install cryptography`). No compile step required
- The private key must be in **PKCS#8 PEM format** — keys exported from Salesforce Certificate & Key Management are already in this format
- The JWT is printed to stdout — safe to pipe or capture into a variable
- Signing algorithm: **RS256** (SHA-256 with RSA) — required by Salesforce JWT Bearer flow
