package com.valleyrealm.valleycertapi.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.valleyrealm.valleycertapi.crypto.CertificateAuthority;
import com.valleyrealm.valleycertapi.model.Certificate;
import com.valleyrealm.valleycertapi.model.CertificateRequest;

import java.util.Date;

import static spark.Spark.*;

/**
 * REST API for certificate operations.
 * 
 * Endpoints:
 * POST /api/certificate/issue - Issue new certificate
 * POST /api/certificate/renew - Renew existing certificate
 * POST /api/certificate/revoke - Revoke certificate
 * GET /api/certificate/validate/:id - Validate certificate
 * GET /api/certificate/:id - Get certificate details
 */
public class CertificateApi {

    private final int port;
    private final CertificateAuthority ca;
    private final Gson gson;

    public CertificateApi(int port, CertificateAuthority ca) {
        this.port = port;
        this.ca = ca;
        this.gson = new GsonBuilder()
            .registerTypeAdapter(java.util.Date.class, (JsonSerializer<Date>) (src, typeOfSrc, context) ->
                new JsonPrimitive(src.getTime()))
            .create();
    }

    /**
     * Start the API server.
     */
    public void start() {
        port(port);
        path("/api", () -> {
            path("/certificate", () -> {
                // Issue new certificate
                post("/issue", (req, res) -> {
                    res.type("application/json");
                    
                    try {
                        CertificateRequest request = gson.fromJson(req.body(), CertificateRequest.class);
                        Certificate cert = ca.issueCertificate(request);
                        return gson.toJson(new CertificateResponse(true, "Certificate issued", cert));
                    } catch (Exception e) {
                        res.status(400);
                        return gson.toJson(new CertificateResponse(false, e.getMessage(), null));
                    }
                });

                // Renew certificate
                post("/renew", (req, res) -> {
                    res.type("application/json");
                    
                    try {
                        RenewRequest request = gson.fromJson(req.body(), RenewRequest.class);
                        Certificate cert = ca.renewCertificate(request.getCertificateId(), request.getValidityDays());
                        return gson.toJson(new CertificateResponse(true, "Certificate renewed", cert));
                    } catch (Exception e) {
                        res.status(400);
                        return gson.toJson(new CertificateResponse(false, e.getMessage(), null));
                    }
                });

                // Revoke certificate
                post("/revoke", (req, res) -> {
                    res.type("application/json");
                    
                    try {
                        RevokeRequest request = gson.fromJson(req.body(), RevokeRequest.class);
                        ca.revokeCertificate(request.getCertificateId(), request.getReason());
                        return gson.toJson(new CertificateResponse(true, "Certificate revoked", null));
                    } catch (Exception e) {
                        res.status(400);
                        return gson.toJson(new CertificateResponse(false, e.getMessage(), null));
                    }
                });

                // Validate certificate
                get("/validate/:id", (req, res) -> {
                    res.type("application/json");
                    
                    String certId = req.params(":id");
                    boolean valid = ca.validateCertificate(certId);
                    
                    return gson.toJson(new ValidationResponse(valid, certId));
                });

                // Get certificate details
                get("/:id", (req, res) -> {
                    res.type("application/json");
                    
                    String certId = req.params(":id");
                    Certificate cert = ca.getStore().getCertificateById(certId);
                    
                    if (cert == null) {
                        res.status(404);
                        return gson.toJson(new CertificateResponse(false, "Certificate not found", null));
                    }
                    
                    return gson.toJson(new CertificateResponse(true, "Certificate found", cert));
                });
            });
        });

        // Error handling
        notFound((req, res) -> {
            res.type("application/json");
            return gson.toJson(new CertificateResponse(false, "Endpoint not found", null));
        });

        internalServerError((req, res) -> {
            res.type("application/json");
            return gson.toJson(new CertificateResponse(false, "Internal server error", null));
        });

        awaitInitialization();
        System.out.println("[CertificateApi] Server started on port " + port);
    }

    /**
     * Stop the API server.
     */
    public void stop() {
        spark.Spark.stop();
        System.out.println("[CertificateApi] Server stopped.");
    }

    // Response classes
    public static class CertificateResponse {
        private final boolean success;
        private final String message;
        private final Certificate certificate;

        public CertificateResponse(boolean success, String message, Certificate certificate) {
            this.success = success;
            this.message = message;
            this.certificate = certificate;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Certificate getCertificate() { return certificate; }
    }

    public static class ValidationResponse {
        private final boolean valid;
        private final String certificateId;

        public ValidationResponse(boolean valid, String certificateId) {
            this.valid = valid;
            this.certificateId = certificateId;
        }

        public boolean isValid() { return valid; }
        public String getCertificateId() { return certificateId; }
    }

    public static class RenewRequest {
        private String certificateId;
        private int validityDays;

        public String getCertificateId() { return certificateId; }
        public int getValidityDays() { return validityDays; }
    }

    public static class RevokeRequest {
        private String certificateId;
        private String reason;

        public String getCertificateId() { return certificateId; }
        public String getReason() { return reason; }
    }
}
