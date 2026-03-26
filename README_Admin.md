# JWT Bearer Flow — SF Admin Setup Guide
### Salesforce OAuth 2.0 · Sandbox & Production

---

## Overview

| Phase | Who | What |
|---|---|---|
| **Phase 1** | SF Admin | Generate certificate in Salesforce Certificate & Key Management, then export private key |
| **Phase 2** | SF Admin | Create & configure External Client App (ECA) in Salesforce Setup |
| **Phase 3** | SF Admin | Collect & securely deliver credentials to the client |

---

## Phase 1 — Generate Certificate & Private Key

### Method 1 (Primary) — Salesforce Certificate and Key Management

This is the security team's approved approach. All key generation happens inside Salesforce — no local certificate tooling required.

#### Step 1 — Create a Self-Signed Certificate in Salesforce

**Setup → Security → Certificate and Key Management → Create Self-Signed Certificate**

Configure with:
- ✅ **Label**: a descriptive name (e.g. `JWT_Auth_Cert`)
- ✅ **Exportable Private Key**: checked
- ✅ **Key Size**: 2048

Click **Save**.

#### Step 2 — Export the Keystore (.jks)

From the Certificate and Key Management list, click **Export to Keystore** next to your certificate. Set a keystore password when prompted — save it securely.

This downloads a `.jks` file (e.g. `onecars.jks`) to your machine.

#### Step 3 — Find the alias in the keystore

```bash
keytool -list -keystore <certname>.jks -storepass <jks_password>
```

Note the alias name shown (e.g. `jwt_niluk`) — you will need it in the next step.

> ⚠️ **keytool requires the JDK** (not just the JRE). If `keytool` is not found, install OpenJDK: `choco install openjdk -y`, then run `refreshenv`.

#### Step 4 — Convert JKS → PKCS12 (.p12)

```bash
keytool -importkeystore \
  -srckeystore <certname>.jks \
  -destkeystore <certname>.p12 \
  -deststoretype PKCS12 \
  -srcalias <alias> \
  -srcstorepass <jks_password> \
  -deststorepass <p12_password> \
  -noprompt
```

#### Step 5 — Convert PKCS12 → PEM (private.key)

```bash
openssl pkcs12 \
  -in <certname>.p12 \
  -nocerts \
  -nodes \
  -out private.key \
  -passin pass:<p12_password>
```

#### Step 6 — Verify the key

```bash
openssl rsa -in private.key -check -noout
# Expected output: RSA key ok
```

#### Step 7 — Remove the intermediate .p12 file

```bash
# Mac / Linux
rm <certname>.p12

# Windows PowerShell
Remove-Item <certname>.p12
```

#### Files produced

| File | Purpose |
|---|---|
| `<certname>.jks` | Salesforce-exported keystore — store securely, do not distribute |
| `private.key` | Private key — deliver to client via secure channel only |

> ⚠️ **Never share `private.key` over email or Slack.** Use a secure channel (1Password, Vault, encrypted ZIP, etc.).

---

### Method 2 (Alternative) — Generate via OpenSSL

Use this method only if Salesforce Certificate and Key Management is unavailable, or if your security team explicitly approves a locally-generated certificate.

#### Step 1 — Generate a password-protected RSA key

```bash
openssl genrsa -des3 -passout pass:x -out server.pass.key 2048
```

#### Step 2 — Strip the passphrase and produce the final private key

```bash
openssl rsa -passin pass:x -in server.pass.key -out server.key
```

> `server.key` is the file you will deliver to the client.

#### Step 3 — Remove the intermediate key file

```bash
# Mac / Linux
rm server.pass.key

# Windows PowerShell
Remove-Item server.pass.key
```

#### Step 4 — Create a Certificate Signing Request (CSR)

```bash
openssl req -new -key server.key -out server.csr
```

Fill in your organisation details (Country, State, Organisation) when prompted.

#### Step 5 — Generate the self-signed certificate (valid 365 days)

```bash
openssl x509 -req -sha256 -days 365 -in server.csr -signkey server.key -out server.crt
```

> `server.crt` is the certificate you will upload to Salesforce ECA.

#### Files produced

| File | Purpose |
|---|---|
| `server.key` | Private key — deliver to client via secure channel |
| `server.crt` | Certificate — upload to Salesforce ECA |
| `server.csr` | CSR — can be kept or discarded |

> ⚠️ **Never share `server.key` over email or Slack.** Use a secure channel (1Password, Vault, encrypted ZIP, etc.).

---

## Phase 2 — Configure External Client App (ECA) in Salesforce

Log in as a Salesforce Administrator. Repeat for each org (Sandbox and/or Production).

### Step 1 — Open App Manager

**Setup → Apps → App Manager → New External Client App**

### Step 2 — Fill in app details

Provide a descriptive name (e.g. *Integration API — JWT*), contact email, and a placeholder callback URL (e.g. `https://localhost/callback`).

### Step 3 — Enable OAuth and select JWT Bearer Flow

Under **OAuth Settings**:
- Check **Enable OAuth Settings**
- Under *Flow Enablement*, select **Enable JWT Bearer Flow**
- Upload `server.crt` in the **Digital Signatures** section
- Add required OAuth scopes (e.g. `api`, `web`, `refresh_token`)

### Step 4 — Save and copy the Consumer Key

After saving, go to the app's **Settings** tab → click **Consumer Key and Secret** → copy the **Consumer Key**.

This is the `consumer_key` / `iss` value for the client's `jwt.properties`.

> Only the Consumer Key is needed for JWT Bearer flow — no client secret is required.

### Step 5 — Set OAuth policies and assign the integration user

**App Manager → Your ECA → Manage → Edit Policies**

- Set **Permitted Users** to *Admin approved users are pre-authorized*
- Under **Profiles** or **Permission Sets**, add the integration user's profile or permission set

### Step 6 — Confirm your org's My Domain URL

**Setup → My Domain** → copy the **Current My Domain URL**

This is the `audience` value for the client's `jwt.properties`.

| Org Type | Audience Format |
|---|---|
| Sandbox | `https://[MyDomain]--[SandboxName].sandbox.my.salesforce.com` |
| Production | `https://[MyDomain].my.salesforce.com` |

---

## Phase 3 — Deliver Credentials to Client

Provide the client with all four values so they can populate `jwt.properties`:

| Property | Value | Where to find it |
|---|---|---|
| `consumer_key` | ECA Consumer Key | Setup → App Manager → Your ECA → Settings tab |
| `username` | Salesforce integration username | Setup → Users |
| `server.key` | Private key file (Phase 1) | ⚠️ Deliver via secure channel only |
| `audience` | Org My Domain URL | Setup → My Domain |

> Refer the client to **README.html** for full instructions on running the script.

---

## Phase 4 — PeopleSoft ECA Setup (QA Sandbox)

> **Jira:** CX-13013 | Parent: CX-12838 (IAC-46)
> This section covers the end-to-end setup for the `JWT_PeopleSoft` External Client App in the QA Sandbox org.

---

### Step 1 — Create Self-Signed Certificate

**Setup → Security → Certificate and Key Management → Create Self-Signed Certificate**

Configure with:
- **Label:** `JWT_PeopleSoft`
- **Key Size:** `2048`
- **☑ Exportable Private Key:** Must be checked

Click **Save**, then click **Export to Keystore** → set a password → save the `.jks` file securely.

---

### Step 2 — Create External Client App

**Setup → External Client App Manager → New External Client App**

| Field | Value |
|---|---|
| **App Name** | `JWT_PeopleSoft` |
| **Contact Email** | Admin email |
| **Callback URL** | `https://test.salesforce.com/services/oauth2/success` |
| **Flow** | JWT Bearer Flow |
| **Digital Signatures** | Upload `JWT_PeopleSoft` certificate |
| **Scopes** | `Manage user data via APIs (api)` |
| **Security** | Require secret for Refresh Token |

Click **Create**, then go to **Settings tab → Consumer Key and Secret → Copy Consumer Key**.

---

### Step 3 — Configure OAuth Policy ⚠️ CRITICAL

> Without this step, all requests fail with `user hasn't approved this consumer`.

1. Go to the ECA detail page → **Edit**
2. **OAuth Policies → Permitted Users:** Set to **"Admin approved users are pre-authorized"**
3. **Profiles → Manage Profiles → Add:**
   - `System Administrator` (for testing)
   - `SysAdmin_API` (for integration users)
   - Any other profiles requiring JWT access
4. Click **Save**

**Verify profile assignment via SOQL:**
```sql
SELECT Parent.Profile.Name, SetupEntityType
FROM SetupEntityAccess
WHERE SetupEntityType = 'ConnectedApplication'
  AND SetupEntityId = '<ECA_CONNECTED_APP_ID>'
```
If this returns 0 records, profiles are NOT assigned — redo this step.

---

### Step 4 — Convert Certificate to Private Key (PEM)

#### Step 4a — Find the alias
```bash
keytool -list -keystore JWT_PeopleSoft.jks -storepass YOUR_PASSWORD
```

#### Step 4b — Convert JKS → PKCS12
```bash
keytool -importkeystore \
  -srckeystore JWT_PeopleSoft.jks \
  -destkeystore JWT_PeopleSoft.p12 \
  -deststoretype PKCS12 \
  -srcalias <ALIAS_FROM_4a> \
  -srcstorepass YOUR_JKS_PASSWORD \
  -deststorepass YOUR_P12_PASSWORD
```

#### Step 4c — Extract private key
```bash
openssl pkcs12 -in JWT_PeopleSoft.p12 -nocerts -nodes \
  -out private.key -passin pass:YOUR_P12_PASSWORD
```

#### Step 4d — Extract public cert (for jwt.io testing)
```bash
openssl pkcs12 -in JWT_PeopleSoft.p12 -clcerts -nokeys \
  -out public.crt -passin pass:YOUR_P12_PASSWORD
```

---

### Step 5 — Test via Postman

#### 5a — Generate JWT at jwt.io

Go to [jwt.io](https://jwt.io) → select **RS256** → fill in payload:

```json
{
  "iss": "<CONSUMER_KEY>",
  "sub": "<SALESFORCE_USERNAME>",
  "aud": "https://test.salesforce.com",
  "exp": <UNIX_TIMESTAMP>
}
```

Calculate `exp` in browser console:
```js
Math.floor(Date.now() / 1000) + 180
```

> ⚠️ `exp` must be a **number** — no quotes. Valid for ~3 minutes only.

Paste `private.key` and `public.crt` in the signature section. Confirm **"Signature Verified"**.

#### 5b — Postman Request

- **Method:** POST
- **URL:** `https://test.salesforce.com/services/oauth2/token`
  *(or `https://cars-commerce--qa.sandbox.my.salesforce.com/services/oauth2/token` if you get `invalid_client`)*
- **Body (x-www-form-urlencoded):**

| Key | Value |
|---|---|
| `grant_type` | `urn:ietf:params:oauth:grant-type:jwt-bearer` |
| `assertion` | `<paste JWT from jwt.io>` |

#### 5c — Expected Response
```json
{
  "access_token": "00D...",
  "scope": "api",
  "instance_url": "https://cars-commerce--qa.sandbox.my.salesforce.com",
  "token_type": "Bearer"
}
```

---

### Step 6 — Handoff to Client Team

Securely deliver these 4 items:

| Item | Source |
|---|---|
| **Consumer Key** | ECA Settings tab (Step 2) |
| **private.key** | PEM file from Step 4c |
| **Username (sub)** | Integration user's Salesforce username |
| **Audience URL** | `https://test.salesforce.com` (sandbox) or `https://login.salesforce.com` (prod) |

> ⚠️ **Never share `private.key` over email or Slack.** Use a secure channel (1Password, Vault, encrypted ZIP).

---

### Common Errors

| Error | Fix |
|---|---|
| `user hasn't approved this consumer` | Add profile to ECA via Manage Profiles (Step 3) |
| `invalid_client` | Try My Domain URL instead of `test.salesforce.com` |
| `expired authorization code` | Generate a fresh `exp` timestamp — must be within 3 minutes |
| `invalid_grant` | Certificate doesn't match private key, or wrong username |

---

### Certificate Details

| Field | Value |
|---|---|
| Label | `JWT_PeopleSoft` |
| Type | Self-Signed |
| Key Size | 2048 |
| Exportable | Yes |
| Expiration | ~1 year from creation |
| Rotation Lead Time | 30 days before expiry |

---

## Certificate Renewal

The self-signed certificate is valid for **365 days**. Before expiry:

**If using Method 1 (Salesforce Certificate & Key Management):**
1. Create a new Self-Signed Certificate in Setup → Certificate and Key Management
2. Re-export the JKS and repeat the keytool/openssl conversion steps
3. Update the ECA Digital Signatures with the new certificate
4. Re-deliver the new `private.key` to the client securely

**If using Method 2 (OpenSSL):**
1. Repeat Phase 1 to generate a new key pair
2. Re-upload the new `server.crt` to the ECA in Salesforce
3. Re-deliver the new `server.key` to the client securely

> Set a calendar reminder 30 days before expiry. An expired certificate causes the token exchange to return an `invalid_grant` error.

| Action | Frequency |
|---|---|
| Renew certificate + re-upload to ECA | Annually (before `server.crt` expiry) |
| Re-deliver `server.key` to client | Only when certificate is renewed |
| Consumer Key rotation | Only if ECA is recreated |

---

## References

- [Create a Private Key and Self-Signed Digital Certificate — Salesforce Developer Docs](https://developer.salesforce.com/docs/atlas.en-us.sfdx_dev.meta/sfdx_dev/sfdx_dev_auth_key_and_cert.htm)
- [OAuth 2.0 JWT Bearer Flow for Server-to-Server Integration — Salesforce Help](https://help.salesforce.com/s/articleView?id=xcloud.remoteaccess_oauth_jwt_flow.htm&language=en_US&type=5)
- [Configure a JWT Bearer Flow — Salesforce Help](https://help.salesforce.com/s/articleView?id=xcloud.configure_oauth_jwt_flow_external_client_apps.htm&language=en_US&type=5)

---

## Notes

- **Method 1** requires the JDK (for `keytool`) and OpenSSL — install JDK via `choco install openjdk -y` on Windows; OpenSSL is available by default on Mac/Linux or via [Win32 OpenSSL](https://slproweb.com/products/Win32OpenSSL.html) on Windows
- **Method 2** requires only OpenSSL — use only when approved by the security team
- The exported `private.key` is in **PEM format** — the client's `GenerateJWT.java` loads it directly with no conversion needed
- Only the certificate (not the private key) goes into Salesforce — Salesforce uses it to verify the JWT signature
- Repeat Phase 2 for each org (Sandbox and Production) — each org gets its own ECA and Consumer Key
- One certificate key pair can be reused across Sandbox and Production ECAs, or separate pairs can be generated per org
