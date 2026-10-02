package com.oak.dms.encryption;

/**
 * Stands in while encryption at rest is turned off and no key service is
 * configured (production before OpenBao is provisioned for it). Nothing new
 * is encrypted; asking it for anything fails with the reason - so an object
 * that somehow has a key row reports a clear error instead of serving
 * ciphertext.
 */
class UnavailableKeyService implements KeyEncryptionService {

    private final String provider;
    private final String reason;

    UnavailableKeyService(String provider, String reason) {
        this.provider = provider;
        this.reason = reason;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public DataKey generateDataKey(String scope) {
        throw new KeyServiceException(reason);
    }

    @Override
    public byte[] unwrap(String scope, String wrappedKey) {
        throw new KeyServiceException(reason);
    }

    @Override
    public String rewrap(String scope, String wrappedKey) {
        throw new KeyServiceException(reason);
    }

    @Override
    public String wrap(String scope, byte[] dataKey) {
        throw new KeyServiceException(reason);
    }

    @Override
    public KeyInfo info(String scope) {
        throw new KeyServiceException(reason);
    }

    @Override
    public KeyInfo rotate(String scope) {
        throw new KeyServiceException(reason);
    }

    @Override
    public void destroy(String scope) {
        throw new KeyServiceException(reason);
    }
}
