package com.valleyrealm.valleycertapi;

import com.valleyrealm.valleycertapi.api.CertificateApi;
import com.valleyrealm.valleycertapi.crypto.CertificateAuthority;
import com.valleyrealm.valleycertapi.storage.CertificateStore;

import java.nio.file.Path;

/**
 * ValleyCertAPI - Certificate Authority Service
 * 
 * This is the root of trust for Valley Auth certificates.
 * 
 * Responsibilities:
 * - Issue certificates to authorized plugins/addons
 * - Validate certificate requests
 * - Manage certificate capabilities
 * - Revoke compromised certificates
 * - Maintain certificate revocation list
 * 
 * Security:
 * - Private key must never be exposed
 * - All operations require authentication
 * - Audit logging for all certificate operations
 */
public class ValleyCertAPI {

    private final CertificateAuthority ca;
    private final CertificateStore store;
    private final CertificateApi api;
    
    private static ValleyCertAPI instance;

    public ValleyCertAPI(int port, Path dataDir) {
        // Initialize storage
        this.store = new CertificateStore(dataDir.resolve("certs"));
        
        // Initialize CA
        this.ca = new CertificateAuthority(store);
        
        // Initialize API server
        this.api = new CertificateApi(port, ca);
        
        instance = this;
        
        System.out.println("[ValleyCertAPI] Initialized on port " + port);
        System.out.println("[ValleyCertAPI] Data directory: " + dataDir);
    }

    /**
     * Start the API server.
     */
    public void start() {
        api.start();
        System.out.println("[ValleyCertAPI] Server started.");
    }

    /**
     * Stop the API server.
     */
    public void stop() {
        api.stop();
        System.out.println("[ValleyCertAPI] Server stopped.");
    }

    /**
     * Get the CA instance.
     */
    public CertificateAuthority getCA() {
        return ca;
    }

    /**
     * Get the singleton instance.
     */
    public static ValleyCertAPI getInstance() {
        return instance;
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        int port = 8443;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0]);
                System.exit(1);
            }
        }

        Path dataDir = Path.of("data");
        
        ValleyCertAPI api = new ValleyCertAPI(port, dataDir);
        api.start();

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
    }
}
