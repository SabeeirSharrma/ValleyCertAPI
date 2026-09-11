package com.valleyrealm.valleycertapi.model;

import java.util.Date;
import java.util.List;

/**
 * Represents a Valley Auth certificate.
 *
 * Revocation is tracked as a single encrypted timestamp per certificate,
 * stored server-side and never exposed in plaintext. There is no separate
 * revocation list/database table. Validity is derived, not stored as a flag:
 *
 *   Certificate check → decrypt revocation timestamp → elapsed = now - timestamp
 *   → elapsed > allowed lifetime? → NO: valid / YES: invalid → request new cert
 *
 * Fail closed on any decrypt/parse error.
 */
public class Certificate {

    private final String certificateId;
    private final String pluginId;
    private final List<Capability> capabilities;
    private final Date issuanceDate;
    private final Date expirationDate;
    private final String issuer;

    private String signature;

    /**
     * Encrypted revocation timestamp. Null means the certificate has not been revoked.
     * When non-null, this is the CA-encrypted form of the revocation timestamp (millis since epoch).
     * Never stored or transmitted in plaintext.
     */
    private String encryptedRevocationTimestamp;

    public Certificate(String certificateId, String pluginId, List<Capability> capabilities,
                      Date issuanceDate, Date expirationDate, String issuer) {
        this.certificateId = certificateId;
        this.pluginId = pluginId;
        this.capabilities = capabilities;
        this.issuanceDate = issuanceDate;
        this.expirationDate = expirationDate;
        this.issuer = issuer;
        this.encryptedRevocationTimestamp = null;
    }

    /**
     * Check if certificate is currently valid.
     * An unencrypted null revocation timestamp means not revoked.
     * This method is a convenience — full revocation checking should use
     * the CA's decrypt-and-elapsed-time logic.
     */
    public boolean isValid() {
        Date now = new Date();
        return encryptedRevocationTimestamp == null &&
               !now.before(issuanceDate) &&
               !now.after(expirationDate);
    }

    /**
     * Check if this certificate has been revoked (has a stored revocation timestamp).
     */
    public boolean isRevoked() {
        return encryptedRevocationTimestamp != null;
    }

    /**
     * Revoke the certificate by storing an encrypted timestamp.
     *
     * @param encryptedTimestamp The CA-encrypted revocation timestamp (Base64-encoded ciphertext)
     */
    public void revoke(String encryptedTimestamp) {
        this.encryptedRevocationTimestamp = encryptedTimestamp;
    }

    /**
     * Convert to bytes for signing (excludes signature).
     */
    public byte[] toBytesWithoutSignature() {
        String data = certificateId + pluginId + capabilities +
                     issuanceDate.getTime() + expirationDate.getTime() + issuer;
        return data.getBytes();
    }

    /**
     * Convert to bytes for signing (includes signature placeholder).
     */
    public byte[] toBytes() {
        return toBytesWithoutSignature();
    }

    // Getters
    public String getCertificateId() { return certificateId; }
    public String getPluginId() { return pluginId; }
    public List<Capability> getCapabilities() { return capabilities; }
    public Date getIssuanceDate() { return issuanceDate; }
    public Date getExpirationDate() { return expirationDate; }
    public String getIssuer() { return issuer; }
    public String getSignature() { return signature; }
    public String getEncryptedRevocationTimestamp() { return encryptedRevocationTimestamp; }

    // Setters
    public void setSignature(String signature) { this.signature = signature; }
    public void setEncryptedRevocationTimestamp(String encryptedTimestamp) { this.encryptedRevocationTimestamp = encryptedTimestamp; }

    // Enums
    public enum CertificateStatus {
        ACTIVE,
        EXPIRED,
        REVOKED,
        SUSPENDED
    }
}
