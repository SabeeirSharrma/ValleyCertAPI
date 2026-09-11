# ValleyCert API

Certificate Authority service for ValleyAuth. Issues, validates, revokes, and renews capability-scoped certificates signed with ECDSA P-256.

## What It Does

ValleyCert is the root of trust for the ValleyAuth ecosystem. Plugins and addons request certificates with specific capabilities (like VLink access or migration permissions). The CA signs them, stores them as JSON, and exposes a REST API for the rest of the system to validate, renew, or revoke them.

Each certificate carries a set of capabilities that define what the holder can do. Capabilities don't stack. VLink access doesn't grant identity linking, which doesn't grant rank sharing. Least privilege by design.

Revocation uses encrypted timestamps rather than a traditional CRL. When a certificate is revoked, the CA encrypts the current timestamp with AES-256-GCM and stores it alongside the certificate. Validation decrypts that timestamp, computes elapsed time, and compares it against the certificate's original lifetime. If the elapsed time exceeds the allowed window, the certificate is invalid. Any decryption or parse error fails closed, the certificate is rejected.

## API Endpoints

All endpoints return JSON. The base path is `/api/certificate`.

| Method | Path | Description |
| -------- | ------ | ------------- |
| `POST` | `/api/certificate/issue` | Issue a new certificate |
| `POST` | `/api/certificate/renew` | Renew an existing certificate |
| `POST` | `/api/certificate/revoke` | Revoke a certificate |
| `GET` | `/api/certificate/validate/:id` | Validate a certificate |
| `GET` | `/api/certificate/:id` | Get certificate details |

### POST /api/certificate/issue

Issue a new certificate for a plugin.

**Request body:**

```json
{
  "pluginId": "my-cool-plugin",
  "capabilities": ["VLINK", "IDENTITY_LINK"],
  "requestedValidityDays": 90
}
```

- `pluginId` (required): unique identifier for the plugin or addon
- `capabilities` (required): list of capabilities to grant. Must be valid: `VLINK`, `IDENTITY_LINK`, `RANK_SHARE`, `MIGRATION_PROVIDER`, `MIGRATION_ACCESS`, `CERTIFICATE_MANAGEMENT`
- `requestedValidityDays` (required): how many days the certificate should live (capped at 120)

**Response (200):**

```json
{
  "success": true,
  "message": "Certificate issued",
  "certificate": {
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "pluginId": "my-cool-plugin",
    "capabilities": [
      { "name": "VLINK", "description": "Allows VLink operations" },
      { "name": "IDENTITY_LINK", "description": "Allows identity linking" }
    ],
    "issuanceDate": "2025-09-10T12:00:00.000Z",
    "expirationDate": "2025-12-09T12:00:00.000Z",
    "issuer": "ValleyAuth Core",
    "signature": "MEUCIQD...",
    "encryptedRevocationTimestamp": null
  }
}
```

**Error (400):**

```json
{
  "success": false,
  "message": "Plugin already has a valid certificate: <id>",
  "certificate": null
}
```

**Example:**

```bash
curl -X POST https://cert.valleyrealm.qd.je/api/certificate/issue \
  -H "Content-Type: application/json" \
  -d '{
    "pluginId": "my-cool-plugin",
    "capabilities": ["VLINK", "IDENTITY_LINK"],
    "requestedValidityDays": 90
  }'
```

### POST /api/certificate/renew

Renew an existing certificate. The old certificate is revoked automatically, and a new one is issued with the same plugin ID and capabilities.

**Request body:**

```json
{
  "certificateId": "550e8400-e29b-41d4-a716-446655440000",
  "validityDays": 90
}
```

- `certificateId` (required): ID of the certificate to renew
- `validityDays` (required): new validity period in days

**Response (200):**

```json
{
  "success": true,
  "message": "Certificate renewed",
  "certificate": { ... }
}
```

**Example:**

```bash
curl -X POST https://cert.valleyrealm.qd.je/api/certificate/renew \
  -H "Content-Type: application/json" \
  -d '{
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "validityDays": 90
  }'
```

### POST /api/certificate/revoke

Revoke a certificate. Stores an AES-GCM encrypted timestamp of when the revocation occurred.

**Request body:**

```json
{
  "certificateId": "550e8400-e29b-41d4-a716-446655440000",
  "reason": "Compromised"
}
```

- `certificateId` (required): ID of the certificate to revoke
- `reason` (optional): human-readable reason (logged, not stored in the certificate)

**Response (200):**

```json
{
  "success": true,
  "message": "Certificate revoked",
  "certificate": null
}
```

**Example:**

```bash
curl -X POST https://cert.valleyrealm.qd.je/api/certificate/revoke \
  -H "Content-Type: application/json" \
  -d '{
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "reason": "Key leaked"
  }'
```

### GET /api/certificate/validate/:id

Check whether a certificate is currently valid. Returns a boolean. This is what downstream services call before trusting a certificate.

Validation checks in order:

1. Certificate exists
2. ECDSA signature is valid
3. Revocation timestamp (if present) decrypts and elapsed time hasn't exceeded the original lifetime
4. Current date falls within the issuance and expiration window

Any failure at step 3 (decryption error, parse error) fails closed and returns `false`.

**Response (200):**

```json
{
  "valid": true,
  "certificateId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Example:**

```bash
curl https://cert.valleyrealm.qd.je/api/certificate/validate/550e8400-e29b-41d4-a716-446655440000
```

### GET /api/certificate/:id

Retrieve the full details of a certificate.

**Response (200):**

```json
{
  "success": true,
  "message": "Certificate found",
  "certificate": {
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "pluginId": "my-cool-plugin",
    "capabilities": [
      { "name": "VLINK", "description": "Allows VLink operations" }
    ],
    "issuanceDate": "2025-09-10T12:00:00.000Z",
    "expirationDate": "2025-12-09T12:00:00.000Z",
    "issuer": "ValleyAuth Core",
    "signature": "MEUCIQD...",
    "encryptedRevocationTimestamp": null
  }
}
```

**Response (404):**

```json
{
  "success": false,
  "message": "Certificate not found",
  "certificate": null
}
```

**Example:**

```bash
curl https://cert.valleyrealm.qd.je/api/certificate/550e8400-e29b-41d4-a716-446655440000
```

## Capabilities

Certificates are scoped to specific capabilities. Each grants one type of access. Having one does not grant another.

| Capability | Description |
| ------------ | ------------- |
| `VLINK` | Allows VLink operations for identity linking |
| `IDENTITY_LINK` | Allows linking identities across platforms |
| `RANK_SHARE` | Allows sharing ranks across linked identities |
| `MIGRATION_PROVIDER` | Allows registering as a migration provider |
| `MIGRATION_ACCESS` | Allows accessing protected migration APIs |
| `CERTIFICATE_MANAGEMENT` | Allows certificate management operations |

## Deployment

ValleyCert is recommended to be self-hosted. You control your own CA keys, certificates, and revocation. The public instance at `cert.valleyrealm.qd.je` is for testing only.

### Prerequisites

- Java 21 or later
- A domain name pointing to your server (e.g., `cert.your-domain.com`)
- Nginx or another reverse proxy for TLS termination
- (Optional) Let's Encrypt for free SSL certificates

### Step 1: Build the JAR

```bash
git clone https://github.com/SabeeirSharrma/ValleyCertAPI.git
cd ValleyCertAPI
./gradlew shadowJar
```

The fat JAR is at `build/libs/valleycert-api-0.1.0-alpha.jar`.

### Step 2: First run

```bash
java -jar build/libs/valleycert-api-0.1.0-alpha.jar 8443
```

On first run, the CA will:

1. Generate an ECDSA P-256 key pair (stored in `data/keys/`)
2. Generate an AES-256 revocation key (stored in `data/keys/revocation.key`)
3. Start the HTTP server on port 8443

Verify it's running:

```bash
curl http://localhost:8443/api/certificate/validate/test
# Should return: {"valid":false,"certificateId":"test"}
```

### Step 3: Set up Nginx

Point your domain at the certapi with TLS. Example Nginx config:

```nginx
server {
    listen 443 ssl;
    server_name cert.your-domain.com;

    ssl_certificate     /etc/letsencrypt/live/cert.your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/cert.your-domain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8443;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Test and reload:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Step 4: Set up as a systemd service

Create `/etc/systemd/system/valleycert.service`:

```ini
[Unit]
Description=ValleyCert API Certificate Authority
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/valleycert
ExecStart=/usr/bin/java -jar build/libs/valleycert-api-0.1.0-alpha.jar 8443
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Adjust `User` and `WorkingDirectory` to your setup. Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable valleycert
sudo systemctl start valleycert
```

Check status:

```bash
sudo systemctl status valleycert
sudo journalctl -u valleycert -f
```

### Step 5: Configure ValleyAuth to use your CA

In your MC server's `plugins/ValleyAuth/config.yml`:

```yaml
certificate:
  api-url: "https://cert.your-domain.com"
```

Restart the MC server. ValleyAuth will request a certificate from your CA on first launch.

### How the flow works

1. ValleyAuth starts and calls `POST /api/certificate/issue` on your CA
2. The CA generates a certificate, signs it with ECDSA P-256, and returns it
3. ValleyAuth stores the certificate locally at `plugins/ValleyAuth/data/core-cert.json`
4. On subsequent startups, ValleyAuth loads the cached certificate
5. If the certificate is expired, ValleyAuth requests a renewal via `POST /api/certificate/renew`
6. If the CA is unreachable, ValleyAuth runs in offline mode using cached certificates
7. Other plugins can validate their certificates via `GET /api/certificate/validate/:id`

### Data storage

All data is stored in `./data/` relative to where you run the JAR:

```
data/
  keys/
    ca-private.key      ECDSA P-256 private key (never share)
    ca-public.key       ECDSA P-256 public key
    revocation.key      AES-256 key for revocation timestamps
  certificates/
    <certificate-id>.json   Individual certificate files
```

**Back up `data/keys/` regularly.** If `ca-private.key` is lost, all certificates must be reissued.

## Building from Source

Prerequisites: Java 21+

```bash
git clone https://github.com/SabeeirSharrma/ValleyCertAPI.git
cd ValleyCertAPI
./gradlew shadowJar
```

The fat JAR lands at `build/libs/valleycertapi-1.0.0.jar`.

To run directly without the JAR:

```bash
./gradlew run
```

### Running the JAR

```bash
java -jar valleycertapi-1.0.0.jar [port]
```

Port defaults to `8443` if not specified. Certificates and keys are stored in `./data/` relative to where you run the JAR.

### Reverse Proxy (Nginx)

The service listens on HTTP internally. Put it behind Nginx for TLS termination:

```nginx
server {
    listen 443 ssl;
    server_name cert.valleyrealm.qd.je;

    ssl_certificate     /etc/letsencrypt/live/cert.valleyrealm.qd.je/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/cert.valleyrealm.qd.je/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8443;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Systemd Service

```ini
[Unit]
Description=ValleyCert API
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/certapi
ExecStart=/usr/bin/java -jar valleycertapi-1.0.0.jar 8443
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

## Security

### Certificate Signing

Every certificate is signed with **ECDSA P-256** (secp256r1) using **SHA-256withECDSA**. The CA key pair is generated on first run and persisted to `data/keys/ca-private.key` and `data/keys/ca-public.key`. The private key never leaves the server.

### Revocation via Encrypted Timestamps

Instead of a traditional CRL or OCSP responder, revocation is tracked as a single **AES-256-GCM** encrypted timestamp per certificate. The revocation key is generated on first run and stored at `data/keys/revocation.key`.

When a certificate is revoked:

1. The current timestamp (milliseconds since epoch) is encrypted with AES-GCM using a random 12-byte IV
2. The IV is prepended to the ciphertext
3. The combined value is Base64-encoded and stored alongside the certificate

When validating:

1. The encrypted timestamp is decrypted
2. Elapsed time is computed: `now - revocationTimestamp`
3. If elapsed time exceeds the certificate's original lifetime (expiration - issuance), the certificate is invalid
4. If the certificate is still within its lifetime window after revocation, it remains valid (grace period)
5. Any decryption or parse error fails closed, the certificate is rejected

### Key Management

| File | Purpose |
| ------ | --------- |
| `data/keys/ca-private.key` | ECDSA P-256 private key (Base64-encoded PKCS#8) |
| `data/keys/ca-public.key` | ECDSA P-256 public key (Base64-encoded X.509) |
| `data/keys/revocation.key` | AES-256 encryption key for revocation timestamps |

Guard these files. If `ca-private.key` is compromised, every certificate signed by this CA must be reissued. If `revocation.key` is compromised, revoked certificates could potentially be decrypted.

### Other Security Notes

- The CA private key is generated once and never exported
- All operations are logged to stdout
- Failed validations fail closed by default
- Certificates are capability-scoped (least privilege)
- The default validity window is 90 days, capped at 120

## Building from Source

Prerequisites: Java 21+

```bash
git clone https://github.com/SabeeirSharrma/ValleyCertAPI.git
cd ValleyCertAPI
./gradlew shadowJar
```

The fat JAR lands at `build/libs/valleycertapi-1.0.0.jar`.

To run directly without the JAR:

```bash
./gradlew run
```

## Project Structure

```
certapi/
  src/main/java/com/valleyrealm/valleycertapi/
    ValleyCertAPI.java          Entry point, wires everything together
    api/CertificateApi.java     SparkJava REST endpoints
    crypto/CertificateAuthority.java  Signing, encryption, validation logic
    storage/CertificateStore.java     JSON file persistence
    model/Certificate.java      Certificate data model
    model/CertificateRequest.java     Issue request model
    model/Capability.java       Capability enum (VLINK, IDENTITY_LINK, etc.)
  build.gradle.kts
```

## License

MIT
