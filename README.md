# ValleyCertAPI

A self-hosted Certificate Authority service for the ValleyAuth ecosystem. Issues, validates, revokes, and renews capability-scoped certificates signed with ECDSA P-256.

## Table of Contents

- [Overview](#overview)
- [What It Does](#what-it-does)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Deployment Guide](#deployment-guide)
- [API Reference](#api-reference)
- [Capabilities](#capabilities)
- [Certificate Flow](#certificate-flow)
- [Security](#security)
- [Data Storage](#data-storage)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [Building from Source](#building-from-source)
- [Project Structure](#project-structure)
- [License](#license)

## Overview

ValleyCertAPI is the root of trust for ValleyAuth. It acts as a private Certificate Authority that issues short-lived, capability-scoped certificates to plugins and addons in the ValleyRealm ecosystem. Each certificate grants only the specific permissions the holder needs, nothing more.

You run your own instance. You control the signing keys, the revocation logic, and the data. The public instance at `cert.valleyrealm.qd.je` exists for testing only.

## What It Does

ValleyCertAPI handles the full certificate lifecycle:

- **Issue** certificates to plugins requesting specific capabilities (VLink access, identity linking, migration permissions, etc.)
- **Validate** certificates on demand, checking signature integrity, expiration, and revocation status
- **Revoke** compromised or unwanted certificates using encrypted timestamps instead of a traditional CRL
- **Renew** certificates by revoking the old one and issuing a fresh replacement with the same capabilities
- **Retrieve** certificate details by ID for inspection and debugging

Every certificate is signed with ECDSA P-256 (secp256r1) using SHA-256withECDSA. The private key is generated once on first run and never leaves the server.

## Architecture

ValleyCertAPI sits between Minecraft plugins and the ValleyAuth core. The flow looks like this:

```
Plugin/Addon
    |
    v
ValleyAuth Core (Minecraft plugin)
    |  - requests certificate with needed capabilities
    |  - caches certificate locally
    |  - validates on each startup
    v
ValleyCertAPI (this service)
    |  - signs with ECDSA P-256
    |  - stores as JSON files
    |  - tracks revocation via encrypted timestamps
    v
Certificate returned to ValleyAuth
```

ValleyAuth Core calls the API on startup to request or validate certificates. Other plugins can call the validation endpoint to check whether a peer holds valid credentials. ValleyCertAPI itself is a standalone SparkJava HTTP server with no database dependency.

## Requirements

- **Java 21** or later
- A **domain name** pointing to your server (e.g., `cert.your-domain.com`)
- **Nginx** (or another reverse proxy) for TLS termination
- **Let's Encrypt** (optional, for free SSL certificates)
- Sufficient disk space for key files and certificate JSON (minimal)

## Quick Start

```bash
git clone https://github.com/SabeeirSharrma/ValleyCertAPI.git
cd ValleyCertAPI
./gradlew shadowJar
java -jar build/libs/valleycertapi-1.0.0.jar 8443
```

Verify it's running:

```bash
curl http://localhost:8443/api/certificate/validate/test
# Returns: {"valid":false,"certificateId":"test"}
```

The CA generated its key pair on first run. You're ready to issue certificates.

## Deployment Guide

### Step 1: Build the JAR

```bash
git clone https://github.com/SabeeirSharrma/ValleyCertAPI.git
cd ValleyCertAPI
./gradlew shadowJar
```

The fat JAR lands at `build/libs/valleycertapi-1.0.0.jar`.

### Step 2: First Run (Key Generation)

```bash
java -jar build/libs/valleycertapi-1.0.0.jar 8443
```

On first run, the CA automatically:

1. Generates an ECDSA P-256 key pair (stored in `data/keys/ca-private.key` and `data/keys/ca-public.key`)
2. Generates an AES-256 revocation encryption key (stored in `data/keys/revocation.key`)
3. Creates the `data/certs/` directory for certificate storage
4. Starts the HTTP server on port 8443

You'll see output like:

```
[CA] Generated and persisted new CA key pair to data/keys
[CA] Generated and persisted new revocation encryption key.
[CertificateStore] Loaded 0 certificates
[ValleyCertAPI] Initialized on port 8443
[ValleyCertAPI] Server started.
```

**Stop the server** after first run (Ctrl+C). You now have keys. Proceed to set up the reverse proxy and systemd service.

### Step 3: Set Up Nginx Reverse Proxy

Point your domain at ValleyCertAPI with TLS. Create an Nginx config file (typically at `/etc/nginx/sites-available/cert.your-domain.com`):

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

If you don't have a TLS certificate yet, get one with Certbot:

```bash
sudo certbot certonly --nginx -d cert.your-domain.com
```

Test and reload Nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Step 4: Set Up a systemd Service

Create `/etc/systemd/system/valleycert.service`:

```ini
[Unit]
Description=ValleyCert API Certificate Authority
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/valleycert
ExecStart=/usr/bin/java -jar build/libs/valleycertapi-1.0.0.jar 8443
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Adjust `User` and `WorkingDirectory` to match your setup. Then enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable valleycert
sudo systemctl start valleycert
```

Check that it's running:

```bash
sudo systemctl status valleycert
sudo journalctl -u valleycert -f
```

### Step 5: Configure ValleyAuth to Use Your CA

In your Minecraft server's `plugins/ValleyAuth/config.yml`:

```yaml
certificate:
  api-url: "https://cert.your-domain.com"
```

Restart the MC server. ValleyAuth will request a certificate from your CA on first launch.

### Step 6: Verify the Integration

After the MC server starts, check the ValleyAuth logs for certificate-related messages. Then hit the API directly to confirm:

```bash
# Check if the certificate was issued (replace <plugin-id> with the actual plugin ID)
curl https://cert.your-domain.com/api/certificate/validate/<plugin-id>

# Or retrieve the certificate details
curl https://cert.your-domain.com/api/certificate/<certificate-id>
```

## API Reference

All endpoints return JSON. The base path is `/api/certificate`. Every response includes a `success` boolean and a `message` string. Errors return HTTP 400 or 404 with a description of what went wrong.

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

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `pluginId` | string | yes | Unique identifier for the plugin or addon |
| `capabilities` | string[] | yes | List of capabilities to grant. Valid values: `VLINK`, `IDENTITY_LINK`, `RANK_SHARE`, `MIGRATION_PROVIDER`, `MIGRATION_ACCESS`, `CERTIFICATE_MANAGEMENT` |
| `requestedValidityDays` | int | yes | How many days the certificate should live. Capped at 120. Minimum 1. |

**Success response (200):**

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

**Error response (400):**

```json
{
  "success": false,
  "message": "Plugin already has a valid certificate: 550e8400-e29b-41d4-a716-446655440000",
  "certificate": null
}
```

Other error cases: missing plugin ID, empty capabilities list, unknown capability name, invalid capability string.

**curl example:**

```bash
curl -X POST https://cert.your-domain.com/api/certificate/issue \
  -H "Content-Type: application/json" \
  -d '{
    "pluginId": "my-cool-plugin",
    "capabilities": ["VLINK", "IDENTITY_LINK"],
    "requestedValidityDays": 90
  }'
```

### POST /api/certificate/renew

Renew an existing certificate. The old certificate is revoked automatically, and a new one is issued with the same plugin ID and capabilities. The old certificate must be currently valid (not expired, not revoked beyond its lifetime window).

**Request body:**

```json
{
  "certificateId": "550e8400-e29b-41d4-a716-446655440000",
  "validityDays": 90
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `certificateId` | string | yes | ID of the certificate to renew |
| `validityDays` | int | yes | New validity period in days |

**Success response (200):**

```json
{
  "success": true,
  "message": "Certificate renewed",
  "certificate": {
    "certificateId": "new-uuid-here",
    "pluginId": "my-cool-plugin",
    "capabilities": [
      { "name": "VLINK", "description": "Allows VLink operations" },
      { "name": "IDENTITY_LINK", "description": "Allows identity linking" }
    ],
    "issuanceDate": "2025-12-09T14:30:00.000Z",
    "expirationDate": "2026-03-09T14:30:00.000Z",
    "issuer": "ValleyAuth Core",
    "signature": "MEUCIQD...",
    "encryptedRevocationTimestamp": null
  }
}
```

**Error response (400):**

```json
{
  "success": false,
  "message": "Certificate not found: 550e8400-e29b-41d4-a716-446655440000",
  "certificate": null
}
```

Other error cases: certificate not found, certificate is invalid (expired or revoked beyond its lifetime window).

**curl example:**

```bash
curl -X POST https://cert.your-domain.com/api/certificate/renew \
  -H "Content-Type: application/json" \
  -d '{
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "validityDays": 90
  }'
```

### POST /api/certificate/revoke

Revoke a certificate. Stores an AES-256-GCM encrypted timestamp of when the revocation occurred. The certificate is not deleted; it remains in storage with its revocation timestamp attached.

**Request body:**

```json
{
  "certificateId": "550e8400-e29b-41d4-a716-446655440000",
  "reason": "Key leaked"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `certificateId` | string | yes | ID of the certificate to revoke |
| `reason` | string | no | Human-readable reason. Logged but not stored in the certificate. |

**Success response (200):**

```json
{
  "success": true,
  "message": "Certificate revoked",
  "certificate": null
}
```

**Error response (400):**

```json
{
  "success": false,
  "message": "Certificate not found: 550e8400-e29b-41d4-a716-446655440000",
  "certificate": null
}
```

**curl example:**

```bash
curl -X POST https://cert.your-domain.com/api/certificate/revoke \
  -H "Content-Type: application/json" \
  -d '{
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "reason": "Key leaked"
  }'
```

### GET /api/certificate/validate/:id

Check whether a certificate is currently valid. Returns a simple JSON object with a boolean. This is what downstream services call before trusting a certificate.

Validation checks in order:

1. **Certificate exists** in storage
2. **ECDSA signature is valid** (verified against the CA public key)
3. **Revocation check**: if a revocation timestamp exists, decrypt it and compute elapsed time. If elapsed time exceeds the certificate's original lifetime (expiration minus issuance), the certificate is invalid. If the certificate is still within its lifetime window after revocation, it remains valid (grace period).
4. **Date range check**: current date falls within the issuance and expiration window.

Any failure at step 3 (decryption error, parse error, corrupted timestamp) fails closed and returns `false`.

**Success response (200):**

```json
{
  "valid": true,
  "certificateId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Invalid response (200):**

```json
{
  "valid": false,
  "certificateId": "550e8400-e29b-41d4-a716-446655440000"
}
```

Note: the validation endpoint always returns HTTP 200, even for invalid certificates. The `valid` field tells you the result. A 404 is only returned if you hit a non-existent endpoint entirely.

**curl example:**

```bash
curl https://cert.your-domain.com/api/certificate/validate/550e8400-e29b-41d4-a716-446655440000
```

### GET /api/certificate/:id

Retrieve the full details of a certificate, including its capabilities, dates, issuer, signature, and revocation status.

**Success response (200):**

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

If the certificate has been revoked, `encryptedRevocationTimestamp` will contain a Base64-encoded string (the AES-GCM encrypted timestamp). It will never be the plaintext timestamp.

**Not found response (404):**

```json
{
  "success": false,
  "message": "Certificate not found",
  "certificate": null
}
```

**curl example:**

```bash
curl https://cert.your-domain.com/api/certificate/550e8400-e29b-41d4-a716-446655440000
```

## Capabilities

Certificates are scoped to specific capabilities. Each grants one type of access. Possessing one capability does not grant another. This is least privilege by design.

| Capability | Description |
|------------|-------------|
| `VLINK` | Allows VLink operations for identity linking |
| `IDENTITY_LINK` | Allows linking identities across platforms |
| `RANK_SHARE` | Allows sharing ranks across linked identities |
| `MIGRATION_PROVIDER` | Allows registering as a migration provider |
| `MIGRATION_ACCESS` | Allows accessing protected migration APIs |
| `CERTIFICATE_MANAGEMENT` | Allows certificate management operations |

A plugin that only needs VLink access should request only `VLINK`. Requesting `IDENTITY_LINK` alongside it does not weaken the certificate, but it does expand the blast radius if the certificate is compromised.

## Certificate Flow

Here's what happens when a ValleyAuth-powered Minecraft server starts up with a fresh install:

1. **Plugin requests certificate**: ValleyAuth Core sends a `POST /api/certificate/issue` to ValleyCertAPI with the plugin's ID and the capabilities it needs (e.g., `VLINK`, `IDENTITY_LINK`).

2. **ValleyCertAPI issues the certificate**: The CA checks that the plugin doesn't already hold a valid certificate, generates a UUID, assigns the requested capabilities, sets the issuance and expiration dates, and signs the certificate with the ECDSA P-256 private key.

3. **Certificate is stored**: The signed certificate is saved as a JSON file in `data/certs/<certificate-id>.json` and cached in memory.

4. **Certificate returned to ValleyAuth**: The full certificate object is returned in the API response. ValleyAuth stores it locally at `plugins/ValleyAuth/data/core-cert.json`.

5. **On subsequent startups**: ValleyAuth loads the cached certificate from disk. If it's still valid, no API call is needed. If it's expired, ValleyAuth requests a renewal via `POST /api/certificate/renew`.

6. **Renewal flow**: The CA revokes the old certificate (encrypts a revocation timestamp), then issues a fresh one with the same plugin ID and capabilities. The old certificate stays in storage with its encrypted revocation timestamp.

7. **Offline mode**: If ValleyCertAPI is unreachable, ValleyAuth runs in offline mode using cached certificates. This is a graceful degradation, not an error.

8. **Peer validation**: Other plugins can call `GET /api/certificate/validate/:id` to check whether a peer plugin holds a valid certificate before granting it access.

## Security

### Certificate Signing

Every certificate is signed with **ECDSA P-256** (secp256r1) using **SHA-256withECDSA**. The signing process:

1. The certificate's identifying data (ID, plugin ID, capabilities, dates, issuer) is serialized to bytes
2. The CA private key signs the byte array
3. The resulting signature is Base64-encoded and stored alongside the certificate

During validation, the same data is reconstructed from the stored certificate, and the signature is verified against the CA public key. If the signature doesn't match, the certificate is rejected.

### Revocation via Encrypted Timestamps

ValleyCertAPI does not use a traditional Certificate Revocation List (CRL) or OCSP responder. Instead, revocation is tracked as a single AES-256-GCM encrypted timestamp per certificate.

**When a certificate is revoked:**

1. The current timestamp (milliseconds since epoch) is converted to a string
2. A random 12-byte IV is generated
3. The timestamp string is encrypted with AES-GCM using the revocation key and the IV
4. The IV is prepended to the ciphertext
5. The combined byte array is Base64-encoded and stored in the certificate's `encryptedRevocationTimestamp` field
6. The certificate is saved to disk

**When validating a certificate with a revocation timestamp:**

1. The Base64 string is decoded
2. The first 12 bytes are extracted as the IV, the rest is ciphertext
3. The ciphertext is decrypted with AES-GCM using the revocation key and IV
4. The plaintext is parsed back to a long (milliseconds since epoch)
5. Elapsed time is computed: `now - revocationTimestamp`
6. The allowed lifetime is computed: `expirationDate - issuanceDate`
7. If `elapsed > allowedLifetime`, the certificate is invalid (revoked and past its grace period)
8. If `elapsed <= allowedLifetime`, the certificate is still within its grace period and considered valid

**Fail-closed behavior**: Any decryption error, Base64 decode error, or parse error during revocation checking causes the certificate to be rejected. The system assumes the worst case when it can't verify the revocation state.

### Key Management

| File | Format | Purpose |
|------|--------|---------|
| `data/keys/ca-private.key` | Base64-encoded PKCS#8 | ECDSA P-256 private key. Signs all certificates. Never share. |
| `data/keys/ca-public.key` | Base64-encoded X.509 | ECDSA P-256 public key. Used to verify signatures. Can be distributed. |
| `data/keys/revocation.key` | Base64-encoded AES-256 | Symmetric key for encrypting/decrypting revocation timestamps. |

**Backup these files.** If `ca-private.key` is lost, every certificate signed by this CA must be reissued from scratch. If `revocation.key` is lost, you can no longer decrypt revocation timestamps, which means revoked certificates will fail closed (rejected) during validation.

The CA key pair is generated on first run using `KeyPairGenerator` with the `secp256r1` curve and `SecureRandom`. It is loaded from disk on subsequent runs. If the existing keys can't be loaded (corrupted file, wrong format), new keys are generated and the old files are overwritten.

The revocation key is generated similarly using `KeyGenerator` for AES-256.

### Other Security Notes

- The CA private key is generated once and never exported through any API endpoint
- All operations are logged to stdout with `[CA]` and `[CertificateApi]` prefixes
- Failed validations fail closed by default
- Certificates are capability-scoped: having `VLINK` does not grant `IDENTITY_LINK`
- The default validity window is 90 days, capped at 120 days, minimum 1 day
- Plugins with an existing valid certificate cannot request a second one (enforced on issue)
- The certificate ID is a random UUID, not predictable

## Data Storage

All data is stored in `./data/` relative to where you run the JAR. The directory structure:

```
data/
  keys/
    ca-private.key          ECDSA P-256 private key (Base64 PKCS#8)
    ca-public.key           ECDSA P-256 public key (Base64 X.509)
    revocation.key          AES-256 encryption key for revocation timestamps
  certs/
    <certificate-id>.json   Individual certificate files (pretty-printed JSON)
```

**Key files** (`data/keys/`): Generated on first run. Never modified after that unless they become corrupted and need regeneration.

**Certificate files** (`data/certs/`): One JSON file per certificate, named by UUID. Contains the full certificate object including the encrypted revocation timestamp (if revoked). Created on issue, updated on revoke, never deleted by the API.

Each certificate JSON file looks like:

```json
{
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
```

## Configuration

### Command Line Arguments

```bash
java -jar valleycertapi-1.0.0.jar [port]
```

| Argument | Default | Description |
|----------|---------|-------------|
| `port` | `8443` | HTTP port to listen on |

There is no config file. The port is the only configurable value. All other settings (data directory, key paths, validity limits) are hardcoded.

### Data Directory

The data directory is always `./data` relative to the working directory where you run the JAR. If you use a systemd service, make sure `WorkingDirectory` is set to the correct path.

### Hardcoded Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `DEFAULT_VALIDITY_DAYS` | 90 | Default certificate lifetime if not specified |
| `MAX_VALIDITY_DAYS` | 120 | Maximum allowed certificate lifetime |
| `CA_NAME` | "ValleyAuth Core" | Issuer field in certificates |
| `GCM_TAG_LENGTH` | 128 | AES-GCM authentication tag length in bits |
| Key directory | `data/keys/` | Where key files are stored |
| Cert directory | `data/certs/` | Where certificate JSON files are stored |

## Troubleshooting

### "Plugin already has a valid certificate"

The plugin already holds a valid certificate that hasn't expired and hasn't been revoked beyond its lifetime window. Either renew the existing certificate or revoke it first, then issue a new one.

### "Certificate not found"

The certificate ID doesn't match any stored certificate. Check the ID for typos. Certificate IDs are UUIDs (e.g., `550e8400-e29b-41d4-a716-446655440000`).

### "Cannot renew invalid certificate"

The certificate being renewed is either expired or has been revoked beyond its lifetime window. You can't renew a dead certificate. Issue a new one instead.

### "Unknown capability"

One or more capability strings in the request don't match the valid set. Check the spelling: capabilities are case-insensitive but must be one of `VLINK`, `IDENTITY_LINK`, `RANK_SHARE`, `MIGRATION_PROVIDER`, `MIGRATION_ACCESS`, `CERTIFICATE_MANAGEMENT`.

### Validation returns false for a seemingly valid certificate

Check the following:

1. **Has it been revoked?** A revoked certificate may still appear valid during its grace period (lifetime elapsed < allowed lifetime). Once that window passes, it's permanently invalid.
2. **Has it expired?** Compare the current date against `expirationDate` in the certificate.
3. **Is the signature invalid?** This could mean the certificate was tampered with, or the CA keys were regenerated (which invalidates all previously signed certificates).

### ValleyAuth can't reach the CA

ValleyAuth runs in offline mode using cached certificates. Verify:

1. The `api-url` in `config.yml` matches your domain
2. Nginx is running and proxying to the correct port
3. The ValleyCertAPI systemd service is active: `sudo systemctl status valleycert`
4. The port is accessible: `curl https://your-domain/api/certificate/validate/test`

### Keys were regenerated

If `ca-private.key` or `ca-public.key` were deleted or corrupted and regenerated, all previously issued certificates will fail signature verification. Every plugin will need a fresh certificate.

### Port already in use

Another process is using the port. Check with `lsof -i :8443` and either stop the conflicting process or choose a different port.

## Building from Source

Prerequisites: Java 21+

```bash
git clone https://github.com/SabeeirSharrma/ValleyCertAPI.git
cd ValleyCertAPI
```

### Build the fat JAR

```bash
./gradlew shadowJar
```

Output: `build/libs/valleycertapi-1.0.0.jar`

### Run directly with Gradle

```bash
./gradlew run
```

This compiles and runs in one step. Useful for development.

### Clean build

```bash
./gradlew clean shadowJar
```

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| SparkJava | 2.9.4 | HTTP server and routing |
| Gson | 2.10.1 | JSON serialization/deserialization |
| Bouncy Castle (prov + pkix) | 1.77 | Cryptographic provider (ECDSA, AES-GCM) |
| SLF4J Simple | 2.0.9 | Logging backend for SparkJava |

## Project Structure

```
certapi/
  src/main/java/com/valleyrealm/valleycertapi/
    ValleyCertAPI.java                 Entry point. Wires storage, CA, and API together.
                                       Parses port from command line args, registers
                                       shutdown hook.

    api/CertificateApi.java            SparkJava REST endpoints. Defines routes for
                                       issue, renew, revoke, validate, and get.
                                       Handles request parsing and response serialization.

    crypto/CertificateAuthority.java   Core CA logic. Generates/loads key pairs, signs
                                       certificates with ECDSA P-256, encrypts/decrypts
                                       revocation timestamps with AES-256-GCM, validates
                                       certificates, manages the certificate lifecycle.

    storage/CertificateStore.java      JSON file persistence. Stores certificates as
                                       individual JSON files in data/certs/. Loads all
                                       certificates into a ConcurrentHashMap on startup.

    model/Certificate.java             Certificate data model. Holds ID, plugin ID,
                                       capabilities, dates, issuer, signature, and
                                       encrypted revocation timestamp.

    model/CertificateRequest.java      Issue request model. Holds plugin ID, capability
                                       list, and requested validity days.

    model/Capability.java              Enum of valid capabilities. VLINK, IDENTITY_LINK,
                                       RANK_SHARE, MIGRATION_PROVIDER, MIGRATION_ACCESS,
                                       CERTIFICATE_MANAGEMENT.
  build.gradle.kts                     Build config. Java 21, Shadow plugin for fat JAR,
                                       SparkJava + Gson + Bouncy Castle dependencies.
```

## License

MIT
