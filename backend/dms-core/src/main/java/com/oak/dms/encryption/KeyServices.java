package com.oak.dms.encryption;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The key services this instance can use: the {@link #primary()} one wraps
 * every new data key; the others are kept so objects wrapped by an earlier
 * service (e.g. the development key file, before switching to OpenBao) can
 * still be unwrapped - and re-wrapped onto the primary by the key screen's
 * re-wrap job. Each stored key row names the service that wrapped it.
 */
public class KeyServices implements AutoCloseable {

    private final KeyEncryptionService primary;
    private final Map<String, KeyEncryptionService> byProvider = new LinkedHashMap<>();

    public KeyServices(KeyEncryptionService primary, KeyEncryptionService... others) {
        this.primary = primary;
        byProvider.put(primary.provider(), primary);
        for (KeyEncryptionService other : others) {
            if (other != null) {
                byProvider.putIfAbsent(other.provider(), other);
            }
        }
    }

    /** Stops background work (OpenBao token renewal) on shutdown. */
    @Override
    public void close() {
        byProvider.values().forEach(service -> {
            if (service instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // shutting down anyway
                }
            }
        });
    }

    public KeyEncryptionService primary() {
        return primary;
    }

    /** The service that wrapped a stored key - unavailable means its objects can't be read right now. */
    public KeyEncryptionService forProvider(String provider) {
        return Optional.ofNullable(byProvider.get(provider)).orElseThrow(() ->
                new KeyEncryptionService.KeyServiceException("Objects wrapped by the '" + provider
                        + "' key service exist, but it isn't configured on this instance"));
    }
}
