package com.valleyrealm.valleycertapi.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.valleyrealm.valleycertapi.model.Certificate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent storage for certificates.
 * 
 * Uses JSON files for simplicity.
 * Production should use a database.
 */
public class CertificateStore {

    private final Path storageDir;
    private final Gson gson;
    private final Map<String, Certificate> certificateCache = new ConcurrentHashMap<>();

    public CertificateStore(Path storageDir) {
        this.storageDir = storageDir;
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
        
        createStorageDir();
        loadCertificates();
    }

    public Path getDataDir() {
        return storageDir;
    }

    /**
     * Get a certificate by plugin ID.
     */
    public Certificate getCertificate(String pluginId) {
        return certificateCache.values().stream()
            .filter(cert -> cert.getPluginId().equals(pluginId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get a certificate by certificate ID.
     */
    public Certificate getCertificateById(String certificateId) {
        return certificateCache.get(certificateId);
    }

    /**
     * Save a certificate.
     */
    public void saveCertificate(Certificate certificate) {
        certificateCache.put(certificate.getCertificateId(), certificate);
        
        Path certPath = getCertificatePath(certificate.getCertificateId());
        try {
            String json = gson.toJson(certificate);
            Files.writeString(certPath, json);
        } catch (IOException e) {
            System.err.println("[CertificateStore] Error saving certificate: " + e.getMessage());
        }
    }

    /**
     * Delete a certificate.
     */
    public boolean deleteCertificate(String certificateId) {
        Certificate cert = certificateCache.remove(certificateId);
        if (cert == null) {
            return false;
        }

        Path certPath = getCertificatePath(certificateId);
        try {
            Files.deleteIfExists(certPath);
            return true;
        } catch (IOException e) {
            System.err.println("[CertificateStore] Error deleting certificate: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get certificate path.
     */
    private Path getCertificatePath(String certificateId) {
        return storageDir.resolve(certificateId + ".json");
    }

    /**
     * Create storage directory.
     */
    private void createStorageDir() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            System.err.println("[CertificateStore] Failed to create directory: " + e.getMessage());
        }
    }

    /**
     * Load all certificates from storage.
     */
    private void loadCertificates() {
        try {
            if (!Files.exists(storageDir)) {
                return;
            }

            Files.list(storageDir)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        String json = Files.readString(path);
                        Certificate cert = gson.fromJson(json, Certificate.class);
                        if (cert != null && cert.getCertificateId() != null) {
                            certificateCache.put(cert.getCertificateId(), cert);
                        }
                    } catch (Exception e) {
                        System.err.println("[CertificateStore] Error loading: " + path + " - " + e.getMessage());
                    }
                });

            System.out.println("[CertificateStore] Loaded " + certificateCache.size() + " certificates");
        } catch (IOException e) {
            System.err.println("[CertificateStore] Error listing directory: " + e.getMessage());
        }
    }
}
