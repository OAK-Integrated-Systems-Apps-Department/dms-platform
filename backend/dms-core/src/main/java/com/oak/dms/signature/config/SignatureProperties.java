package com.oak.dms.signature.config;

import com.oak.dms.encryption.EncryptionProperties;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 1.4 Digital Signature Integration (module DSI, INTERNAL signing only - see
 * {@code com.oak.dms.signature}'s package docs). Mirrors
 * {@link EncryptionProperties}'s own openbao/local-dev/require-managed-keys
 * shape, deliberately with its OWN {@link #openbao} connection rather than
 * reusing {@code dms.encryption.openbao} - signing and encryption are least-
 * privilege-separated OpenBao tokens/policies even when they point at the
 * same server (see docs/storage-architecture.md's policy note).
 */
@Component
@ConfigurationProperties(prefix = "dms.signature")
@Getter
@Setter
public class SignatureProperties {

    /** Off by default: while off, every signature endpoint 404s as if it doesn't exist (same convention as watermark). */
    private boolean enabled = false;

    /** Step-up re-authentication (password, + TOTP when MFA is enabled) before every signature. Disable only for tests. */
    private boolean requireStepUp = true;

    /**
     * Allows {@code LocalDevDocumentSigner} (a file-backed key, for development only) when the provider
     * below is local-dev. {@link SignatureConfig} refuses to start if this is true while
     * {@code dms.encryption.require-managed-keys} is also true - production can't mix a managed-keys
     * requirement with a locally-signed, unmanaged signature key.
     */
    private boolean allowLocalDev = true;

    /** openbao (production) or local-dev (development only, gated by {@link #allowLocalDev}). */
    private String provider = "local-dev";

    /** Where the development signing key lives, when {@link #provider} is local-dev. */
    private String localDevKeyFile = "/var/lib/dms-signature/local-dev-signing-key.json";

    /** Reuses {@link EncryptionProperties.OpenBao}'s shape; {@code keyName} here is the signing-key name PREFIX ("dms-sig"), not a single shared key. */
    private final EncryptionProperties.OpenBao openbao = openBaoDefaults();

    private static EncryptionProperties.OpenBao openBaoDefaults() {
        EncryptionProperties.OpenBao openBao = new EncryptionProperties.OpenBao();
        openBao.setKeyName("dms-sig");
        return openBao;
    }
}
