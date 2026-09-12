package com.valleyrealm.valleycertapi.model;

import java.util.List;

/**
 * Request to issue a new certificate.
 */
public class CertificateRequest {

    private String pluginId;
    private List<String> capabilities;
    private int requestedValidityDays;

    // No-arg constructor for Gson deserialization (Java 21 compatibility)
    public CertificateRequest() {
        this.pluginId = "";
        this.capabilities = List.of();
        this.requestedValidityDays = 30;
    }

    public CertificateRequest(String pluginId, List<String> capabilities, int requestedValidityDays) {
        this.pluginId = pluginId;
        this.capabilities = capabilities;
        this.requestedValidityDays = requestedValidityDays;
    }

    // Getters
    public String getPluginId() { return pluginId; }
    public List<String> getCapabilities() { return capabilities; }
    public int getRequestedValidityDays() { return requestedValidityDays; }
}
