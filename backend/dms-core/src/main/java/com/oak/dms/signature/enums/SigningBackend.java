package com.oak.dms.signature.enums;

/**
 * Which {@code DocumentSigner} implementation produced a key - stored on
 * {@code user_signing_keys.backend} for diagnostics; never affects
 * verification, which only ever uses the stored public key (JCA
 * SHA256withECDSA), never this field.
 */
public enum SigningBackend {
    transit, local_dev;

    /** The CHECK constraint's own spelling ("local-dev", a hyphen, not valid as a Java identifier). */
    public String wireValue() {
        return this == local_dev ? "local-dev" : name();
    }

    public static SigningBackend fromWireValue(String value) {
        return "local-dev".equals(value) ? local_dev : SigningBackend.valueOf(value);
    }
}
