package com.valleyrealm.valleycertapi.model;

/**
 * Certificate capabilities.
 * 
 * Each capability grants access to specific Valley Auth functionality.
 * Possessing one capability does not grant another (least privilege).
 */
public enum Capability {
    /**
     * Allows using VLink for identity linking.
     */
    VLINK("VLINK", "Allows VLink operations"),
    
    /**
     * Allows linking identities.
     */
    IDENTITY_LINK("IDENTITY_LINK", "Allows identity linking"),
    
    /**
     * Allows sharing ranks across linked identities.
     */
    RANK_SHARE("RANK_SHARE", "Allows rank sharing"),
    
    /**
     * Allows registering as a migration provider.
     */
    MIGRATION_PROVIDER("MIGRATION_PROVIDER", "Allows migration provider registration"),
    
    /**
     * Allows accessing protected migration APIs.
     */
    MIGRATION_ACCESS("MIGRATION_ACCESS", "Allows protected migration API access"),
    
    /**
     * Allows certificate management operations.
     */
    CERTIFICATE_MANAGEMENT("CERTIFICATE_MANAGEMENT", "Allows certificate management");

    private final String name;
    private final String description;

    Capability(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get capability by name.
     */
    public static Capability fromString(String name) {
        for (Capability cap : values()) {
            if (cap.name.equalsIgnoreCase(name)) {
                return cap;
            }
        }
        return null;
    }

    /**
     * Check if a capability name is valid.
     */
    public static boolean isValid(String name) {
        return fromString(name) != null;
    }
}
