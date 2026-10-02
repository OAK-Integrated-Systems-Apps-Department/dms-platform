package com.oak.dms.encryption;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Encryption at rest (Module DE) - see {@link EncryptingObjectStorageService}
 * and docs/storage-architecture.md.
 */
@Component
@ConfigurationProperties(prefix = "dms.encryption")
@Getter
@Setter
public class EncryptionProperties {

    /** Encrypt every object the application writes. Off only for tests that need raw storage. */
    private boolean enabled = true;

    /** openbao (production) or local-dev (a key file for development only). */
    private String provider = "local-dev";

    /**
     * Refuse to start unless a managed key service (OpenBao) holds the keys.
     * Production sets this, so a development key file can never protect real data.
     */
    private boolean requireManagedKeys = false;

    /** Ciphertext segment size of the streaming AEAD; each segment carries its own authentication tag. */
    private int segmentSizeBytes = 1024 * 1024;

    /**
     * Refuse to start unless OpenBao has an audit device enabled - the log where every unwrap of a
     * document's key is recorded (DE-NFR-006). Production sets it. Needs read + sudo on sys/audit.
     */
    private boolean requireAuditDevice = false;

    /**
     * How long an unwrapped data key may be reused from memory instead of asking the key service
     * again - one bulk export or a burst of previews then costs one request per key, not per read.
     * Zero turns the cache off. Keys are wiped from memory when they leave it.
     */
    private java.time.Duration keyCacheTtl = java.time.Duration.ofMinutes(5);
    private int keyCacheMaxEntries = 10_000;

    private final LocalDev localDev = new LocalDev();
    private final OpenBao openbao = new OpenBao();
    private final Rotation rotation = new Rotation();

    /** The master-key rotation schedule (DE-FR-005) - each tenant can change its own on the key screen. */
    @Getter
    @Setter
    public static class Rotation {
        /** A tenant without its own setting is due this many days after its last rotation. 0 = no schedule. */
        private int defaultIntervalDays = 365;
        /** How often the schedule is checked (once across all instances). */
        private java.time.Duration checkInterval = java.time.Duration.ofHours(6);
        /** A key that is due but not auto-rotated is reminded about again after this long. */
        private java.time.Duration remindEvery = java.time.Duration.ofDays(7);
    }

    @Getter
    @Setter
    public static class LocalDev {
        /**
         * Where the development master keys live - outside the database and
         * git, on a volume of its own. Losing it makes local-dev encrypted
         * objects unreadable, so the service refuses to start with a missing
         * file once objects encrypted with it exist.
         */
        private String keyFile = "/var/lib/dms-encryption/local-dev-keys.json";
        /**
         * A second copy kept in step with {@link #keyFile}, somewhere that survives losing its volume
         * (locally: a gitignored folder of the repository). Restored automatically when the key file is
         * missing. Blank = no backup.
         */
        private String backupFile = "";
    }

    @Getter
    @Setter
    public static class OpenBao {
        /** e.g. https://openbao.internal:8200 */
        private String address = "";
        /** approle (production: role id + secret id, renewed and re-issued automatically) or token. */
        private String auth = "token";
        private String roleId = "";
        /** From the secret store, never committed. */
        private String secretId = "";
        private String appRoleMount = "approle";
        /** Token with the transit policy for {@link #keyName} only (from the secrets env, never committed). */
        private String token = "";
        /** Transit secrets-engine mount path. */
        private String mount = "transit";
        /** The Transit key that wraps every data key. */
        private String keyName = "dms-documents";
    }
}
