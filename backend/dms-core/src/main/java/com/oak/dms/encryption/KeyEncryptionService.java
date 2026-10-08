package com.oak.dms.encryption;

import java.util.UUID;

/**
 * The key service that wraps (encrypts) and unwraps the per-object data
 * keys with a master key that never leaves it - OpenBao Transit in
 * production (DE-FR-004, DE-NFR-001). Implementations must be thread-safe.
 * <p>
 * Master keys are per <em>scope</em>: each tenant has its own (V57), so one
 * tenant's key never opens another's documents and a tenant admin's rotation
 * touches only their own data. {@link #PLATFORM} covers objects written
 * outside any tenant. A scope's master key is created on first use.
 */
public interface KeyEncryptionService {

    /** The scope for objects written outside any tenant - and every key from before V57. */
    String PLATFORM = "platform";

    static String scopeOf(UUID tenantId) {
        return tenantId == null ? PLATFORM : tenantId.toString();
    }

    /** "openbao" or "local-dev" - stored with every wrapped key. */
    String provider();

    /** A fresh random 256-bit data key, its wrapped form and the master-key version that wrapped it. */
    DataKey generateDataKey(String scope);

    /** The data key back, for decrypting at the point of access (DE-FR-003). */
    byte[] unwrap(String scope, String wrappedKey);

    /** The same data key wrapped with the scope's latest master-key version - the data key never leaves the service. */
    String rewrap(String scope, String wrappedKey);

    /** Wraps an existing data key under a scope's latest version - for moving a key to another scope or service. */
    String wrap(String scope, byte[] dataKey);

    /** Many keys at once - a bulk export, a re-wrap batch. A key that can't be unwrapped comes back null. */
    default java.util.List<byte[]> unwrapAll(String scope, java.util.List<String> wrappedKeys) {
        java.util.List<byte[]> out = new java.util.ArrayList<>(wrappedKeys.size());
        for (String wrapped : wrappedKeys) {
            try {
                out.add(unwrap(scope, wrapped));
            } catch (KeyServiceException e) {
                out.add(null);
            }
        }
        return out;
    }

    /** {@link #rewrap} for many keys; a key that can't be re-wrapped comes back null. */
    default java.util.List<String> rewrapAll(String scope, java.util.List<String> wrappedKeys) {
        java.util.List<String> out = new java.util.ArrayList<>(wrappedKeys.size());
        for (String wrapped : wrappedKeys) {
            try {
                out.add(rewrap(scope, wrapped));
            } catch (KeyServiceException e) {
                out.add(null);
            }
        }
        return out;
    }

    KeyInfo info(String scope);

    /** Adds a new master-key version; new data keys use it, older ones stay unwrappable until re-wrapped. */
    KeyInfo rotate(String scope);

    /**
     * Crypto-shredding: destroys every version of a tenant's master key, so no
     * data key it wrapped can ever be unwrapped again. Irreversible. A scope
     * whose key is already gone is fine (it can be retried); the platform key
     * is never destroyed.
     */
    void destroy(String scope);

    /** Refuses to destroy the shared platform key - it isn't any one tenant's. */
    static void requireTenantScope(String scope) {
        if (PLATFORM.equals(scope)) {
            throw new IllegalArgumentException("The platform key is shared and is never destroyed");
        }
    }

    /** @param plaintext never logged or stored */
    record DataKey(byte[] plaintext, String wrapped, int version) {
    }

    /**
     * @param minDecryptionVersion the oldest version still allowed to unwrap
     * @param managed              true for a real key service; false for the development key file
     */
    record KeyInfo(String provider, String keyName, int latestVersion, int minDecryptionVersion,
                   boolean managed) {
    }

    /** The key service refused or couldn't be reached - nothing can be encrypted or decrypted. */
    class KeyServiceException extends RuntimeException {
        public KeyServiceException(String message) {
            super(message);
        }

        public KeyServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The master-key version of an "anything:vN:payload" wrapped key. */
    static int versionOf(String wrappedKey) {
        String[] parts = wrappedKey == null ? new String[0] : wrappedKey.split(":", 3);
        if (parts.length < 3 || !parts[1].startsWith("v")) {
            throw new KeyServiceException("Not a wrapped key");
        }
        try {
            return Integer.parseInt(parts[1].substring(1));
        } catch (NumberFormatException e) {
            throw new KeyServiceException("Not a wrapped key", e);
        }
    }
}
