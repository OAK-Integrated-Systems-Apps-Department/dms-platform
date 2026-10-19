package com.oak.dms.signature.service;

import com.oak.dms.signature.enums.SigningBackend;

/**
 * Produces ECDSA P-256 / SHA-256 signatures for one named key, never
 * verifies one - verification only ever uses the public key stored on the
 * {@code document_signatures} row (plain JCA {@code SHA256withECDSA}, see
 * {@link SignatureVerifier}), never this interface, so a signer backend
 * being down can never block reading old evidence.
 * <p>
 * Two implementations: {@link TransitDocumentSigner} (production, OpenBao
 * Transit, the private key never leaves OpenBao) and
 * {@link LocalDevDocumentSigner} (development only, a file-backed key pair -
 * see {@link com.oak.dms.signature.config.SignatureConfig} for when each is
 * allowed to start).
 */
public interface DocumentSigner {

    SigningBackend backend();

    /** Creates {@code keyName} if it doesn't exist yet; returns its PEM-encoded public key either way. */
    String ensureKey(String keyName);

    /** A DER-encoded (ASN.1) ECDSA signature of a SHA-256 digest the caller already computed. */
    byte[] sign(String keyName, byte[] sha256Digest);
}
