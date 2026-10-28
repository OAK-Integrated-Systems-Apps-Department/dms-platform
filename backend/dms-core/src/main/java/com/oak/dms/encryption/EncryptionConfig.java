package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Picks the key services for encryption at rest and refuses to start with an
 * unsafe setup:
 * <ul>
 *   <li>production (require-managed-keys) must use OpenBao;</li>
 *   <li>a development key file can't silently be replaced once objects
 *       depend on it, and one that doesn't match the stored keys (an old or
 *       wrong file restored) is refused at start, not discovered later;</li>
 *   <li>the other key service stays available when it's still configured,
 *       so objects wrapped before a switch remain readable until re-wrapped.</li>
 * </ul>
 * Turning encryption off only stops encrypting NEW objects - already
 * encrypted ones are still decrypted, as long as their key service is reachable.
 */
@Slf4j
@Configuration
public class EncryptionConfig {

    @Bean
    public ObjectCipher objectCipher(EncryptionProperties properties) {
        return new ObjectCipher(properties.getSegmentSizeBytes());
    }

    @Bean
    public KeyServices keyServices(EncryptionProperties properties, ObjectMapper mapper,
                                   StorageObjectKeyRepository keys) {
        String provider = properties.getProvider();
        boolean enabled = properties.isEnabled();
        if (OpenBaoTransitKeyService.PROVIDER.equals(provider)) {
            KeyEncryptionService primary = openBao(properties, mapper, enabled);
            // Objects wrapped by the development key file before the switch stay readable.
            return new KeyServices(primary, existingLocalDev(properties, mapper, keys));
        }
        if (!LocalDevKeyService.PROVIDER.equals(provider)) {
            throw new IllegalStateException("Unknown dms.encryption.provider '" + provider
                    + "' - use openbao (production) or local-dev (development)");
        }
        if (enabled && properties.isRequireManagedKeys()) {
            throw new IllegalStateException("dms.encryption.require-managed-keys is set but the provider is "
                    + "local-dev - production must use dms.encryption.provider=openbao");
        }
        Path keyFile = Path.of(properties.getLocalDev().getKeyFile());
        Path backup = backupOf(properties);
        boolean objectsDependOnIt = keys.existsByKeyProvider(LocalDevKeyService.PROVIDER);
        KeyEncryptionService primary;
        if (!enabled && !Files.exists(keyFile) && (backup == null || !Files.exists(backup)) && !objectsDependOnIt) {
            log.warn("Encryption at rest is OFF - new objects are stored unencrypted");
            primary = new UnavailableKeyService(provider, "Encryption at rest is turned off.");
        } else {
            LocalDevKeyService service = new LocalDevKeyService(keyFile, backup, mapper, !objectsDependOnIt);
            verifyAgainstStoredKeys(service, keys);
            log.warn("Encryption at rest {} with the DEVELOPMENT key file {} - not for production",
                    enabled ? "ON" : "OFF", keyFile);
            primary = service;
        }
        // Objects re-wrapped onto OpenBao earlier stay readable while OpenBao is still configured.
        return new KeyServices(primary, optionalOpenBao(properties, mapper));
    }

    private static KeyEncryptionService openBao(EncryptionProperties properties, ObjectMapper mapper, boolean enabled) {
        EncryptionProperties.OpenBao openbao = properties.getOpenbao();
        boolean signInConfigured = "approle".equalsIgnoreCase(openbao.getAuth())
                ? !openbao.getRoleId().isBlank() && !openbao.getSecretId().isBlank()
                : !openbao.getToken().isBlank();
        if (!enabled && (openbao.getAddress().isBlank() || !signInConfigured)) {
            log.warn("Encryption at rest is OFF and OpenBao isn't configured - new objects are stored unencrypted");
            return new UnavailableKeyService(OpenBaoTransitKeyService.PROVIDER,
                    "Encryption at rest is turned off and OpenBao isn't configured.");
        }
        OpenBaoTransitKeyService service = new OpenBaoTransitKeyService(RestClient.builder(), mapper, openbao);
        service.ensureKey(KeyEncryptionService.PLATFORM); // reachable, signed in - or don't start
        checkAuditDevice(service, properties.isRequireAuditDevice());
        log.info("Encryption at rest {}: data keys wrapped by OpenBao transit keys {}/{}[-<tenant>]",
                enabled ? "ON" : "OFF", openbao.getMount(), openbao.getKeyName());
        return service;
    }

    /**
     * DE-NFR-006: every unwrap of a document key must be logged - by OpenBao's audit device. Production
     * (require-audit-device) refuses to start without one, or when OpenBao won't say; elsewhere a warning.
     */
    static void checkAuditDevice(OpenBaoTransitKeyService service, boolean required) {
        List<String> devices;
        try {
            devices = service.auditDevices();
        } catch (KeyEncryptionService.KeyServiceException e) {
            String message = "Could not confirm that OpenBao has an audit device enabled (" + e.getMessage()
                    + ") - the token's policy needs read and sudo on sys/audit";
            if (required) {
                throw new IllegalStateException(message, e);
            }
            log.warn(message);
            return;
        }
        if (devices.isEmpty()) {
            String message = "OpenBao has no audit device enabled - key access would not be logged (DE-NFR-006). "
                    + "Enable one, e.g. bao audit enable file file_path=/openbao/logs/audit.log";
            if (required) {
                throw new IllegalStateException(message);
            }
            log.warn(message);
        } else {
            log.info("OpenBao audit devices enabled: {}", devices);
        }
    }

    /** OpenBao as a secondary service: used only for objects it wrapped; never blocks startup. */
    private static KeyEncryptionService optionalOpenBao(EncryptionProperties properties, ObjectMapper mapper) {
        EncryptionProperties.OpenBao openbao = properties.getOpenbao();
        boolean signIn = "approle".equalsIgnoreCase(openbao.getAuth())
                ? !openbao.getRoleId().isBlank() && !openbao.getSecretId().isBlank()
                : !openbao.getToken().isBlank();
        if (openbao.getAddress().isBlank() || !signIn) {
            return null;
        }
        try {
            return new OpenBaoTransitKeyService(RestClient.builder(), mapper, openbao);
        } catch (RuntimeException e) {
            log.warn("OpenBao is configured but unusable ({}) - objects it wrapped can't be read", e.getMessage());
            return null;
        }
    }

    /** The development key file as a secondary service, when it still exists. */
    private static KeyEncryptionService existingLocalDev(EncryptionProperties properties, ObjectMapper mapper,
                                                         StorageObjectKeyRepository keys) {
        Path keyFile = Path.of(properties.getLocalDev().getKeyFile());
        Path backup = backupOf(properties);
        boolean present = Files.exists(keyFile) || (backup != null && Files.exists(backup));
        if (!present) {
            if (keys.existsByKeyProvider(LocalDevKeyService.PROVIDER)) {
                log.warn("Objects wrapped by the development key file exist but the file is gone - re-wrapping "
                        + "them onto OpenBao is impossible until it's restored");
            }
            return null;
        }
        LocalDevKeyService service = new LocalDevKeyService(keyFile, backup, mapper, false);
        verifyAgainstStoredKeys(service, keys);
        return service;
    }

    /**
     * Unwraps one stored key per scope and version: a key file that doesn't match the database (an old
     * backup, another machine's file) fails here, at start, with a clear message - not on some later download.
     */
    static void verifyAgainstStoredKeys(LocalDevKeyService service, StorageObjectKeyRepository keys) {
        for (Object[] sample : keys.sampleWrappedKeys(LocalDevKeyService.PROVIDER)) {
            String scope = (String) sample[0];
            String wrapped = (String) sample[1];
            try {
                byte[] dataKey = service.unwrap(scope, wrapped);
                Arrays.fill(dataKey, (byte) 0);
            } catch (KeyEncryptionService.KeyServiceException e) {
                throw new IllegalStateException("The development key file doesn't match the encrypted objects "
                        + "(scope " + scope + ", version " + KeyEncryptionService.versionOf(wrapped) + "): "
                        + e.getMessage() + ". Restore the key file those objects were encrypted with.", e);
            }
        }
    }

    private static Path backupOf(EncryptionProperties properties) {
        String backup = properties.getLocalDev().getBackupFile();
        return backup == null || backup.isBlank() ? null : Path.of(backup);
    }
}
