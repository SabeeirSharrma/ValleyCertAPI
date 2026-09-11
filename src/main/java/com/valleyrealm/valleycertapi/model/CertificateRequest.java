package com.valleyrealm.valleycertapi.model;

import java.util.List;

/**
 * Request to issue a new certificate.
 */
public class CertificateRequest {

    private final String pluginId;
    private final List<String> capabilities;
    private final int requestedValidityDays;

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
