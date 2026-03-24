# JWT Bearer Flow — SF Admin Setup Guide
### Salesforce OAuth 2.0 · Sandbox & Production

---

## Overview

| Phase | Who | What |
|---|---|---|
| **Phase 1** | SF Admin / DevOps | Generate private key & self-signed certificate using OpenSSL |
| **Phase 2** | SF Admin | Create & configure External Client App (ECA) in Salesforce Setup |
| **Phase 3** | SF Admin | Collect & securely deliver credentials to the client |

---

## Phase 1 — Generate Private Key & Certificate

Use OpenSSL to generate an RSA private key and a self-signed X.509 certificate.

### Step 1 — Generate a password-protected RSA key

```bash
openssl genrsa -des3 -passout pass:x -out server.pass.key 2048
```

### Step 2 — Strip the passphrase and produce the final private key

```bash
openssl rsa -passin pass:x -in server.pass.key -out server.key
```

> `server.key` is the file you will deliver to the client.

### Step 3 — Remove the intermediate key file

```bash
# Mac / Linux
rm server.pass.key

# Windows PowerShell
Remove-Item server.pass.key
```

### Step 4 — Create a Certificate Signing Request (CSR)

```bash
openssl req -new -key server.key -out server.csr
```

Fill in your organisation details (Country, State, Organisation) when prompted.

### Step 5 — Generate the self-signed certificate (valid 365 days)

```bash
openssl x509 -req -sha256 -days 365 -in server.csr -signkey server.key -out server.crt
```

> `server.crt` is the certificate you will upload to Salesforce.

### Files produced

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

## Certificate Renewal

The self-signed certificate is valid for **365 days**. Before expiry:
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

- OpenSSL must be installed to run Phase 1 commands — available by default on Mac/Linux; install via [Win32 OpenSSL](https://slproweb.com/products/Win32OpenSSL.html) on Windows
- The generated `server.key` is in **PKCS#8 PEM format** — the client's `GenerateJWT.java` loads it directly with no conversion needed
- Only `server.crt` goes into Salesforce — Salesforce uses it to verify the JWT signature without ever seeing the private key
- Repeat Phase 2 for each org (Sandbox and Production) — each org gets its own ECA and Consumer Key
- One certificate key pair can be reused across Sandbox and Production ECAs, or separate pairs can be generated per org
