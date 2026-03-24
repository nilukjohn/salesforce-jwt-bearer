# JWT Bearer Token Generator
### Salesforce OAuth 2.0 — Client Instructions

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

## Configuration

All JWT parameters are stored in `jwt.properties`. Update this file with your org values before running.

| Property | Description | Example |
|---|---|---|
| `consumer_key` | ECA Consumer Key (JWT `iss`) | Found in Salesforce Setup → External Client Apps |
| `username` | Salesforce integration username (JWT `sub`) | `integration@yourorg.com` |
| `audience` | Your org's My Domain URL (JWT `aud`) | See Audience section below |
| `private_key` | Path to RS256 PEM private key file | `server.key` |
| `expiry` | Token lifetime in seconds — max 180 | `180` |

### Audience (`aud`) — My Domain URL

Set `audience` to your org's **My Domain URL**, not the generic Salesforce endpoints. This is the recommended approach per Salesforce documentation.

| Org Type | Format | Example |
|---|---|---|
| **Sandbox** | `https://[MyDomain]--[SandboxName].sandbox.my.salesforce.com` | `https://mycompany--qa.sandbox.my.salesforce.com` |
| **Production** | `https://[MyDomain].my.salesforce.com` | `https://mycompany.my.salesforce.com` |

> Your My Domain URL can be found in Salesforce Setup → **My Domain**.

---

## PowerShell Commands

Open PowerShell and navigate to this folder:

```powershell
cd C:\path\to\your\JWT\folder
```

Three runtime options are available — all use the same `jwt.properties` config file.

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

5. Under **Sign JWT**, set **Private Key Format** to `PEM` and paste the contents of your `server.key` file
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

## Reference

- [Salesforce OAuth 2.0 JWT Bearer Flow for Server-to-Server Integration](https://help.salesforce.com/s/articleView?id=xcloud.remoteaccess_oauth_jwt_flow.htm&language=en_US&type=5)

---

## Notes

- All three scripts (`GenerateJWT.java`, `GenerateJWT.js`, `GenerateJWT.py`) share the same interface and `jwt.properties` config
- **Java**: No external libraries — uses built-in `java.security` package only. Recompile (`javac GenerateJWT.java`) only needed when the `.java` file changes
- **Node.js**: No external packages — uses built-in `crypto` module. No compile step required
- **Python**: Requires the `cryptography` package (`pip install cryptography`). No compile step required
- The private key must be in **PKCS#8 PEM format** — keys exported from Salesforce Certificate & Key Management are already in this format
- The JWT is printed to stdout — safe to pipe or capture into a variable
- Signing algorithm: **RS256** (SHA-256 with RSA) — required by Salesforce JWT Bearer flow
