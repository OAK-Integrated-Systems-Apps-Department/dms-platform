package com.oak.dms.signature.service;

import com.oak.dms.encryption.OpenBaoTransitKeyService;
import com.oak.dms.signature.enums.SigningBackend;

/**
 * Production signer: every key and every signature operation stays in
 * OpenBao Transit - the private key is never exportable and never seen by
 * this application. Thin adapter over {@link OpenBaoTransitKeyService}'s own
 * {@code ensureSigningKey}/{@code sign}/{@code signingPublicKeyPem} methods,
 * using a dedicated {@code OpenBaoTransitKeyService} instance built from
 * {@code dms.signature.openbao.*} (see {@code SignatureConfig}) - a separate
 * connection/token from the one encryption-at-rest uses, even against the
 * same OpenBao server.
 */
public class TransitDocumentSigner implements DocumentSigner {

    private final OpenBaoTransitKeyService transit;

    public TransitDocumentSigner(OpenBaoTransitKeyService transit) {
        this.transit = transit;
    }

    @Override
    public SigningBackend backend() {
        return SigningBackend.transit;
    }

    @Override
    public String ensureKey(String keyName) {
        transit.ensureSigningKey(keyName);
        return transit.signingPublicKeyPem(keyName);
    }

    @Override
    public byte[] sign(String keyName, byte[] sha256Digest) {
        return transit.sign(keyName, sha256Digest);
    }
}
