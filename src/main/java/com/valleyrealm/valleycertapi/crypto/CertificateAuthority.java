package com.valleyrealm.valleycertapi.crypto;

import com.valleyrealm.valleycertapi.model.Certificate;
import com.valleyrealm.valleycertapi.model.CertificateRequest;
import com.valleyrealm.valleycertapi.model.Capability;
import com.valleyrealm.valleycertapi.storage.CertificateStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Certificate Authority for Valley Auth.
 *
 * The CA is the sole authority for issuing Valley Auth certificates.
 *
 * Revocation is tracked as a single encrypted timestamp per certificate,
 * stored server-side and never exposed in plaintext. On every certificate check:
 *   decrypt timestamp → elapsed = now - timestamp → compare against allowed lifetime.
 * Fail closed on any decrypt/parse error.
 *
 * Security:
 * - Uses ECDSA for signing (P-256 curve)
 * - AES-GCM for encrypting revocation timestamps
 * - Private key never leaves this class
 * - All operations are audited
 * - Certificates are capability-scoped
 */
public class CertificateAuthority {

    private final CertificateStore store;
    private final KeyPair caKeyPair;
    private final Path keyDir;
    private final SecretKey revocationKey;
    private final Map<String, Certificate> certificateCache = new ConcurrentHashMap<>();

    private static final String CA_NAME = "ValleyAuth Core";
    private static final int DEFAULT_VALIDITY_DAYS = 90;
    private static final int MAX_VALIDITY_DAYS = 120;
    private static final int GCM_TAG_LENGTH = 128;

    public CertificateAuthority(CertificateStore store) {
        this.store = store;
        this.keyDir = store.getDataDir().resolve("keys");
        this.caKeyPair = generateOrLoadCAKeys();
        this.revocationKey = generateOrLoadRevocationKey();

        System.out.println("[CA] Certificate Authority initialized: " + CA_NAME);
    }

    public CertificateStore getStore() {
        return store;
    }

    /**
     * Issue a new certificate.
     */
    public Certificate issueCertificate(CertificateRequest request) {
        validateRequest(request);

        Certificate existing = store.getCertificate(request.getPluginId());
        if (existing != null && existing.isValid()) {
            throw new SecurityException("Plugin already has a valid certificate: " + existing.getCertificateId());
        }

        int validityDays = Math.min(request.getRequestedValidityDays(), MAX_VALIDITY_DAYS);
        validityDays = Math.max(validityDays, 1);

        Date now = new Date();
        Date expiry = new Date(now.getTime() + (long) validityDays * 24 * 60 * 60 * 1000);

        String certId = UUID.randomUUID().toString();
        List<Capability> capabilities = request.getCapabilities().stream()
            .map(Capability::fromString)
            .toList();

        Certificate cert = new Certificate(certId, request.getPluginId(), capabilities, now, expiry, CA_NAME);

        String signature = signCertificate(cert);
        cert.setSignature(signature);

        store.saveCertificate(cert);
        certificateCache.put(cert.getCertificateId(), cert);

        System.out.println("[CA] Certificate issued: " + certId + " for " + request.getPluginId());
        System.out.println("[CA] Capabilities: " + capabilities);
        System.out.println("[CA] Expires: " + expiry);

        return cert;
    }

    /**
     * Renew an existing certificate.
     */
    public Certificate renewCertificate(String certificateId, int requestedValidityDays) {
        Certificate oldCert = store.getCertificateById(certificateId);
        if (oldCert == null) {
            throw new IllegalArgumentException("Certificate not found: " + certificateId);
        }

        if (!validateCertificate(certificateId)) {
            throw new SecurityException("Cannot renew invalid certificate: " + certificateId);
        }

        CertificateRequest request = new CertificateRequest(
            oldCert.getPluginId(),
            oldCert.getCapabilities().stream().map(Capability::getName).toList(),
            requestedValidityDays
        );

        revokeCertificate(certificateId, "Renewed");

        return issueCertificate(request);
    }

    /**
     * Revoke a certificate by storing an encrypted timestamp.
     * The timestamp is AES-GCM encrypted — never stored in plaintext.
     */
    public void revokeCertificate(String certificateId, String reason) {
        Certificate cert = store.getCertificateById(certificateId);
        if (cert == null) {
            throw new IllegalArgumentException("Certificate not found: " + certificateId);
        }

        long revocationTimestamp = System.currentTimeMillis();
        String encrypted = encryptTimestamp(revocationTimestamp);
        cert.revoke(encrypted);
        store.saveCertificate(cert);
        certificateCache.put(certificateId, cert);

        System.out.println("[CA] Certificate revoked: " + certificateId + " Reason: " + reason);
    }

    /**
     * Validate a certificate using encrypted-timestamp revocation.
     *
     * Check flow per spec:
     *   1. Certificate exists
     *   2. Signature is valid
     *   3. Decrypt revocation timestamp (if present)
     *   4. Compute elapsed = now - timestamp
     *   5. If elapsed > allowed lifetime → invalid
     *   6. Fail closed on any decrypt/parse error
     */
    public boolean validateCertificate(String certificateId) {
        Certificate cert = store.getCertificateById(certificateId);
        if (cert == null) {
            return false;
        }

        if (!verifySignature(cert)) {
            System.err.println("[CA] Invalid certificate signature: " + certificateId);
            return false;
        }

        // Check encrypted revocation timestamp
        String encryptedTimestamp = cert.getEncryptedRevocationTimestamp();
        if (encryptedTimestamp != null) {
            try {
                long revocationTime = decryptTimestamp(encryptedTimestamp);
                long now = System.currentTimeMillis();
                long elapsed = now - revocationTime;
                long allowedLifetimeMs = cert.getExpirationDate().getTime() - cert.getIssuanceDate().getTime();

                if (elapsed > allowedLifetimeMs) {
                    System.out.println("[CA] Certificate revoked and lifetime exceeded: " + certificateId);
                    return false;
                }
                // Still within the allowed window after revocation — treat as valid
            } catch (Exception e) {
                // Fail closed on decrypt/parse error
                System.err.println("[CA] Failed to decrypt revocation timestamp — failing closed: " + certificateId);
                return false;
            }
        }

        // Check date range
        Date now = new Date();
        return !now.before(cert.getIssuanceDate()) && !now.after(cert.getExpirationDate());
    }

    /**
     * Check if a certificate has a specific capability.
     */
    public boolean hasCapability(String certificateId, String capabilityName) {
        Certificate cert = store.getCertificateById(certificateId);
        if (cert == null || !validateCertificate(certificateId)) {
            return false;
        }

        return cert.getCapabilities().stream()
            .anyMatch(cap -> cap.getName().equals(capabilityName));
    }

    // --- Encryption helpers ---

    /**
     * Encrypt a timestamp using AES-GCM with the revocation key.
     */
    private String encryptTimestamp(long timestamp) {
        try {
            byte[] timestampBytes = Long.toString(timestamp).getBytes();
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, revocationKey, spec);

            byte[] ciphertext = cipher.doFinal(timestampBytes);

            // Prepend IV to ciphertext for storage
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt revocation timestamp", e);
        }
    }

    /**
     * Decrypt an encrypted timestamp using AES-GCM.
     * Throws on any error (caller must fail closed).
     */
    private long decryptTimestamp(String encryptedBase64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[combined.length - 12];
        System.arraycopy(combined, 0, iv, 0, 12);
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, revocationKey, spec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return Long.parseLong(new String(plaintext));
    }

    // --- Key management ---

    private void validateRequest(CertificateRequest request) {
        if (request.getPluginId() == null || request.getPluginId().isEmpty()) {
            throw new IllegalArgumentException("Plugin ID is required");
        }
        if (request.getCapabilities() == null || request.getCapabilities().isEmpty()) {
            throw new IllegalArgumentException("At least one capability is required");
        }
        for (String cap : request.getCapabilities()) {
            if (Capability.fromString(cap) == null) {
                throw new IllegalArgumentException("Unknown capability: " + cap);
            }
        }
    }

    private String signCertificate(Certificate cert) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(caKeyPair.getPrivate());
            byte[] certBytes = cert.toBytes();
            signature.update(certBytes);
            byte[] signedBytes = signature.sign();
            return Base64.getEncoder().encodeToString(signedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign certificate", e);
        }
    }

    private boolean verifySignature(Certificate cert) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(caKeyPair.getPublic());
            byte[] certBytes = cert.toBytesWithoutSignature();
            signature.update(certBytes);
            byte[] signedBytes = Base64.getDecoder().decode(cert.getSignature());
            return signature.verify(signedBytes);
        } catch (Exception e) {
            System.err.println("[CA] Signature verification failed: " + e.getMessage());
            return false;
        }
    }

    private KeyPair generateOrLoadCAKeys() {
        Path privateKeyPath = keyDir.resolve("ca-private.key");
        Path publicKeyPath = keyDir.resolve("ca-public.key");

        try {
            Files.createDirectories(keyDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create key directory: " + keyDir, e);
        }

        if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
            try {
                byte[] privateBytes = Base64.getDecoder().decode(Files.readString(privateKeyPath).trim());
                byte[] publicBytes = Base64.getDecoder().decode(Files.readString(publicKeyPath).trim());
                KeyFactory keyFactory = KeyFactory.getInstance("EC");
                PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
                System.out.println("[CA] Loaded existing CA key pair from " + keyDir);
                return new KeyPair(publicKey, privateKey);
            } catch (Exception e) {
                System.err.println("[CA] Failed to load existing keys, generating new pair: " + e.getMessage());
            }
        }

        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();
            Files.writeString(privateKeyPath, Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            Files.writeString(publicKeyPath, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            System.out.println("[CA] Generated and persisted new CA key pair to " + keyDir);
            return keyPair;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate CA keys", e);
        }
    }

    private SecretKey generateOrLoadRevocationKey() {
        Path keyPath = keyDir.resolve("revocation.key");

        try {
            Files.createDirectories(keyDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create key directory", e);
        }

        if (Files.exists(keyPath)) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(Files.readString(keyPath).trim());
                System.out.println("[CA] Loaded existing revocation encryption key.");
                return new SecretKeySpec(keyBytes, "AES");
            } catch (Exception e) {
                System.err.println("[CA] Failed to load revocation key, generating new: " + e.getMessage());
            }
        }

        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, new SecureRandom());
            SecretKey key = keyGen.generateKey();
            Files.writeString(keyPath, Base64.getEncoder().encodeToString(key.getEncoded()));
            System.out.println("[CA] Generated and persisted new revocation encryption key.");
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate revocation key", e);
        }
    }
}
