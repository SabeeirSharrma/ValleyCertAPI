# ValleyCertAPI Documentation

**Version:** v0.2.0-alpha
**Status:** Active Development
**License:** Proprietary (ValleyRealm Ecosystem)

---

## Overview

ValleyCertAPI is a self-hosted Certificate Authority service built for the ValleyAuth ecosystem. It issues, validates, revokes, and renews capability-scoped certificates signed with ECDSA P-256. You run your own instance. There are no cloud dependencies, no external CAs, no account sign-ups. You deploy it, it generates its own keys, and it starts issuing certificates.

Certificates in ValleyCertAPI are not X.509. They are lightweight JSON documents signed by your CA's private key. Each certificate binds a plugin identity to a set of authorized capabilities. ValleyAuth Core checks these certificates when plugins attempt restricted operations.

This documentation covers installation, configuration, API reference, architecture, storage structure, troubleshooting, and frequently asked questions.

---

## Architecture

```
+-------------------+       +---------------------+       +----------------+
| Plugin / Addon    | ----> | ValleyAuth Core     | ----> | ValleyCertAPI  |
| (requests caps)   |       | (Minecraft plugin)  |       | (CA service)   |
+-------------------+       +---------------------+       +----------------+
                                    |                           |
                                    v                           v
                            +---------------+           +---------------+
                            | Local cache   |           | JSON file     |
                            | of certs      |           | store on disk |
                            +---------------+           +---------------+
```

**Request flow:**

1. A plugin requests specific capabilities (e.g., `VLINK`, `IDENTITY_LINK`).
2. ValleyAuth Core forwards the request to ValleyCertAPI via HTTP.
3. ValleyCertAPI checks if the plugin already holds a valid certificate. If not, it issues one.
4. The signed certificate is returned to ValleyAuth Core, which passes it back to the plugin.
5. When the plugin performs a restricted action, ValleyAuth Core sends the certificate to ValleyCertAPI for validation.
6. ValleyCertAPI verifies the signature, checks expiry, checks revocation status, and returns a validation result.

---

## Capabilities

Each certificate is scoped to one or more capabilities. A plugin can only use the capabilities listed in its certificate.

| Capability | Description |
|---|---|
| `VLINK` | Basic plugin linking and communication |
| `IDENTITY_LINK` | Cross-plugin identity binding |
| `RANK_SHARE` | Share rank or permission data between plugins |
| `MIGRATION_PROVIDER` | Provide data during plugin migration |
| `MIGRATION_ACCESS` | Access data during plugin migration |
| `CERTIFICATE_MANAGEMENT` | Manage certificates (issue, revoke, renew) |

Certificates with `CERTIFICATE_MANAGEMENT` carry elevated trust. Issue them carefully.

---

## Requirements

| Requirement | Minimum |
|---|---|
| Java | 21 or newer |
| Disk space | ~50 MB (JAR + runtime data) |
| RAM | 256 MB recommended |
| OS | Any OS with Java 21 support |

ValleyCertAPI is distributed as a Shadow JAR (fat JAR with all dependencies bundled). No build tools are required at runtime.

---

## Deployment

### Building from source

Clone the repository and build:

```bash
cd certapi
./gradlew clean shadowJar
```

The output JAR is in `build/libs/`. It includes all dependencies.

### Running the server

```bash
java -jar valleycert-api-0.2.0-alpha.jar [port]
```

| Argument | Default | Description |
|---|---|---|
| `port` | `8443` | HTTP port the API listens on |

First run generates the CA key pair. Subsequent runs load the existing keys.

### Data directory structure

On startup, ValleyCertAPI creates `./data/` relative to the working directory:

```
data/
  certs/
    keys/
      ca-private.key       # ECDSA P-256 private key (never leave the server)
      ca-public.key        # ECDSA P-256 public key
      revocation.key       # AES-GCM key for encrypted revocation timestamps
    <certificateId>.json   # One file per certificate
```

### systemd service

Create `/etc/systemd/system/certapi.service`:

```ini
[Unit]
Description=ValleyCertAPI Certificate Authority
After=network.target

[Service]
Type=simple
User=certapi
Group=certapi
WorkingDirectory=/opt/certapi
ExecStart=/usr/bin/java -jar valleycert-api-0.2.0-alpha.jar 8443
Restart=on-failure
RestartSec=5

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/certapi/data

[Install]
WantedBy=multi-user.target
```

Create the service user and set permissions:

```bash
sudo useradd -r -s /bin/false certapi
sudo mkdir -p /opt/certapi
sudo cp build/libs/valleycert-api-0.2.0-alpha.jar /opt/certapi/
sudo chown -R certapi:certapi /opt/certapi

sudo systemctl daemon-reload
sudo systemctl enable certapi
sudo systemctl start certapi
```

### nginx reverse proxy

For HTTPS termination in front of ValleyCertAPI:

```nginx
server {
    listen 443 ssl;
    server_name certapi.yourdomain.com;

    ssl_certificate     /etc/ssl/certs/certapi.pem;
    ssl_certificate_key /etc/ssl/private/certapi.key;

    location /api/ {
        proxy_pass http://127.0.0.1:8443;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Reload nginx after applying:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

---

## API Reference

All endpoints live under `/api/certificate`. The base URL is whatever host and port you started the server on.

**Content type:** All request bodies use `application/json`. All responses are `application/json`.

### POST /api/certificate/issue

Issue a new certificate for a plugin.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `pluginId` | string | yes | Unique identifier of the plugin requesting the certificate |
| `capabilities` | string[] | yes | List of capability names the plugin needs |
| `requestedValidityDays` | integer | no | How many days the certificate should be valid (default: 365) |

**Example request:**

```bash
curl -X POST http://localhost:8443/api/certificate/issue \
  -H "Content-Type: application/json" \
  -d '{
    "pluginId": "my-awesome-plugin",
    "capabilities": ["VLINK", "IDENTITY_LINK"],
    "requestedValidityDays": 90
  }'
```

**Success response (200):**

```json
{
  "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "pluginId": "my-awesome-plugin",
  "capabilities": ["VLINK", "IDENTITY_LINK"],
  "issuedAt": 1726166400000,
  "expiresAt": 1726252800000,
  "issuer": "ValleyCertAPI",
  "signature": "MEUCIQD..."
}
```

**Error response (400):**

```json
{
  "error": "Plugin 'my-awesome-plugin' already has a valid certificate. Use renew instead."
}
```

**Date format note:** All dates are epoch milliseconds (long), not formatted strings. This is critical for client compatibility with ValleyAuth Core.

### POST /api/certificate/renew

Renew an existing certificate. This revokes the old certificate and issues a fresh one.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `certificateId` | string | yes | ID of the certificate to renew |
| `validityDays` | integer | no | New validity period in days (default: 365) |

**Example request:**

```bash
curl -X POST http://localhost:8443/api/certificate/renew \
  -H "Content-Type: application/json" \
  -d '{
    "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "validityDays": 180
  }'
```

**Success response (200):**

```json
{
  "certificateId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "pluginId": "my-awesome-plugin",
  "capabilities": ["VLINK", "IDENTITY_LINK"],
  "issuedAt": 1726166400000,
  "expiresAt": 1726339200000,
  "issuer": "ValleyCertAPI",
  "signature": "MEUCIQD..."
}
```

The old certificate is automatically revoked with reason `RENEWED`. A new certificate ID is generated.

**Error response (400):**

```json
{
  "error": "Certificate 'a1b2c3d4-e5f6-7890-abcd-ef1234567890' not found."
}
```

### POST /api/certificate/revoke

Revoke a certificate. Revoked certificates fail validation immediately.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `certificateId` | string | yes | ID of the certificate to revoke |
| `reason` | string | no | Why the certificate is being revoked (logged but not embedded) |

**Example request:**

```bash
curl -X POST http://localhost:8443/api/certificate/revoke \
  -H "Content-Type: application/json" \
  -d '{
    "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "reason": "Plugin compromised"
  }'
```

**Success response (200):**

```json
{
  "message": "Certificate revoked successfully.",
  "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "revokedAt": 1726166400000
}
```

**How revocation works:** ValleyCertAPI does not use traditional Certificate Revocation Lists (CRLs). Instead, revocation stores an AES-GCM encrypted timestamp inside the certificate file. During validation, the CA decrypts this timestamp and checks whether it exists. If it does, the certificate is revoked. This approach avoids distributing revocation lists and keeps the revocation state co-located with the certificate itself.

**Error response (400):**

```json
{
  "error": "Certificate 'a1b2c3d4-e5f6-7890-abcd-ef1234567890' not found."
}
```

### GET /api/certificate/validate/:id

Validate a certificate by its ID. Checks signature, expiry, and revocation status.

**Path parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | string | The certificate ID to validate |

**Example request:**

```bash
curl http://localhost:8443/api/certificate/validate/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Success response (200):**

```json
{
  "valid": true,
  "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Invalid certificate response (200):**

```json
{
  "valid": false,
  "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

The response always returns 200 with a `valid` boolean. It does not return 404 or other error codes. A certificate is considered valid only if all three conditions are met:

1. The signature is verified against the CA public key.
2. The current time is before the expiry date.
3. The certificate has not been revoked (no encrypted revocation timestamp present).

### GET /api/certificate/:id

Retrieve full certificate details by ID.

**Path parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | string | The certificate ID to retrieve |

**Example request:**

```bash
curl http://localhost:8443/api/certificate/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Success response (200):**

```json
{
  "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "pluginId": "my-awesome-plugin",
  "capabilities": ["VLINK", "IDENTITY_LINK"],
  "issuedAt": 1726166400000,
  "expiresAt": 1726252800000,
  "issuer": "ValleyCertAPI",
  "signature": "MEUCIQD...",
  "encryptedRevocationTimestamp": null
}
```

If the certificate has been revoked, `encryptedRevocationTimestamp` contains the encrypted value. Otherwise it is `null`.

**Error response (404):**

```json
{
  "error": "Certificate not found."
}
```

### GET /api/certificate/plugin/:pluginId

Retrieve a certificate by its associated plugin ID.

**Path parameters:**

| Parameter | Type | Description |
|---|---|---|
| `pluginId` | string | The plugin identifier to look up |

**Example request:**

```bash
curl http://localhost:8443/api/certificate/plugin/my-awesome-plugin
```

**Success response (200):**

```json
{
  "certificateId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "pluginId": "my-awesome-plugin",
  "capabilities": ["VLINK", "IDENTITY_LINK"],
  "issuedAt": 1726166400000,
  "expiresAt": 1726252800000,
  "issuer": "ValleyCertAPI",
  "signature": "MEUCIQD..."
}
```

**Error response (404):**

```json
{
  "error": "No certificate found for plugin 'my-awesome-plugin'."
}
```

---

## Certificate Model

Every certificate stored on disk and returned by the API follows this structure:

| Field | Type | Description |
|---|---|---|
| `certificateId` | string | UUID assigned at issuance |
| `pluginId` | string | Plugin that owns this certificate |
| `capabilities` | string[] | Authorized capability names |
| `issuedAt` | long | Epoch millis when the certificate was issued |
| `expiresAt` | long | Epoch millis when the certificate expires |
| `issuer` | string | Always `"ValleyCertAPI"` |
| `signature` | string | ECDSA P-256 signature of the certificate content |
| `encryptedRevocationTimestamp` | string or null | AES-GCM encrypted timestamp if revoked, null otherwise |

---

## Data Storage

Certificates are stored as individual JSON files in `data/certs/`. Each file is named after its `certificateId` with a `.json` extension.

```
data/certs/
  a1b2c3d4-e5f6-7890-abcd-ef1234567890.json
  b2c3d4e5-f6a7-8901-bcde-f12345678901.json
  c3d4e5f6-a7b8-9012-cdef-123456789012.json
  keys/
    ca-private.key
    ca-public.key
    revocation.key
```

The in-memory cache loads all certificates on startup. Writes go to both disk and memory. If the disk write fails, the in-memory state is rolled back and an error is returned. This prevents phantom certificates that exist in memory but not on disk.

---

## Security

### Key management

On first run, ValleyCertAPI generates:

- **ECDSA P-256 key pair** for signing certificates. The private key signs; the public key verifies.
- **AES-GCM key** for encrypting revocation timestamps.

All keys live in `data/certs/keys/`. The private key never leaves the server. No endpoint exposes key material.

### What is not yet implemented

This version has **no authentication on API endpoints**. Anyone who can reach the API can issue, revoke, or renew certificates. This is acceptable for development and testing, or when the API is behind a network layer that controls access (e.g., only localhost, VPN, or firewall rules).

Do not expose a ValleyCertAPI instance directly to the public internet without adding authentication first.

### Revocation design

Revocation does not use CRLs or OCSP. When a certificate is revoked:

1. A timestamp is generated.
2. The timestamp is encrypted with AES-GCM using the revocation key.
3. The encrypted blob is stored in the certificate's `encryptedRevocationTimestamp` field.
4. The certificate file is rewritten to disk.

During validation, the CA attempts to decrypt the field. If decryption succeeds, the certificate is revoked. This approach keeps revocation state in the certificate itself, avoids distribution problems, and resists tampering (the encrypted blob can only be produced with the revocation key).

---

## Troubleshooting

### Server fails to start with port already in use

```
Exception in thread "main" java.net.BindException: Address already in use
```

Another process is using the port. Either stop that process or start ValleyCertAPI on a different port:

```bash
java -jar valleycert-api-0.2.0-alpha.jar 9443
```

### CA keys missing after moving data directory

ValleyCertAPI expects keys in `data/certs/keys/` relative to its working directory. If you moved the data directory, make sure the working directory is correct, or copy the keys to the expected location.

### Certificate files not persisting

Check that the process user has write permissions to the `data/` directory. If running via systemd, verify the `ReadWritePaths` directive includes the data path.

```bash
ls -la /opt/certapi/data/certs/
```

### Validation always returns false

Common causes:

1. The certificate has expired. Check `expiresAt` against the current time.
2. The certificate was revoked. Check for a non-null `encryptedRevocationTimestamp`.
3. The certificate was signed by a different CA instance. Each ValleyCertAPI instance has its own key pair. Certificates from one instance are not valid on another.

### Out of memory

ValleyCertAPI is lightweight, but if you have thousands of certificates, increase heap size:

```bash
java -Xmx512m -jar valleycert-api-0.2.0-alpha.jar
```

### Dates look wrong in responses

Remember, all dates are epoch milliseconds. 1726166400000 is September 12, 2024 at 16:00 UTC. Convert with:

```bash
date -d @1726166400
```

Or in JavaScript:

```javascript
new Date(1726166400000)
```

---

## FAQ

**Q: Can I use ValleyCertAPI certificates as standard X.509 certificates?**

No. ValleyCertAPI issues JSON-based certificates signed with ECDSA. They are not compatible with TLS/SSL, HTTPS, or any system that expects X.509. They are purpose-built for the ValleyAuth plugin trust model.

**Q: Can multiple ValleyCertAPI instances share the same data directory?**

Not reliably. The in-memory cache assumes single-instance ownership of the data files. Running multiple instances against the same directory will cause data corruption. Use one instance per data directory.

**Q: What happens if I delete a certificate file manually?**

The certificate disappears from the API. It was never revoked, so if a plugin somehow retained a copy, validation would still succeed (the CA can verify the signature without the file on disk). To properly remove a certificate, use the revoke endpoint.

**Q: How long are certificates valid by default?**

365 days. You can override this per certificate by passing `requestedValidityDays` in the issue request or `validityDays` in the renew request.

**Q: Does ValleyCertAPI support certificate chains or intermediate CAs?**

No. There is one root CA per instance. No intermediate CAs, no chain validation.

**Q: Can I back up my CA keys?**

Yes. Back up `data/certs/keys/` entirely. If you lose the keys, you cannot validate certificates signed by the old CA. You would need to regenerate keys and reissue all certificates.

**Q: What is the maximum number of capabilities per certificate?**

There is no hard limit. In practice, request only the capabilities the plugin needs. Overly broad capability grants increase the blast radius if a plugin is compromised.

**Q: Can I run ValleyCertAPI behind a load balancer?**

You can terminate TLS at the load balancer and forward to ValleyCertAPI. However, since there is no authentication, the load balancer is your only access control layer. Do not expose multiple ValleyCertAPI instances without ensuring they share the same data directory (which is not supported, see above).

**Q: How do I migrate from one server to another?**

1. Stop ValleyCertAPI on the old server.
2. Copy the entire `data/` directory to the new server.
3. Start ValleyCertAPI on the new server.
4. Update ValleyAuth Core to point to the new address.
5. All existing certificates remain valid.

---

## Class Reference

For developers reading the source code:

| Class | Purpose |
|---|---|
| `ValleyCertAPI.java` | Entry point. Wires up the CA, store, and API server. Parses CLI args (port). |
| `CertificateAuthority.java` | Core CA logic. Issue, renew, revoke, validate. Signs certificates with ECDSA P-256. |
| `CertificateApi.java` | Spark REST API. Defines routes, request/response classes, CORS handling. |
| `CertificateStore.java` | JSON file-based storage with in-memory cache. Handles read/write/sync. |
| `Certificate.java` | Certificate data model. All fields match the API response shape. |
| `CertificateRequest.java` | Request model for the issue endpoint. |
| `Capability.java` | Enum of all valid capability names. |

---

## Version History

| Version | Date | Changes |
|---|---|---|
| v0.2.0-alpha | 2024 | Renewal endpoint, encrypted revocation timestamps, capability-scoped certificates |
| v0.1.0-alpha | 2024 | Initial release. Issue, validate, revoke. Basic ECDSA signing. |
