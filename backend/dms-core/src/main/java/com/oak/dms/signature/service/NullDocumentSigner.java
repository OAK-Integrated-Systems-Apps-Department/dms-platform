package com.oak.dms.signature.service;

import com.oak.dms.signature.enums.SigningBackend;
import com.oak.dms.signature.exception.SignatureException;

/**
 * The {@link DocumentSigner} bean while {@code dms.signature.enabled=false} -
 * exists only so the rest of this package (and {@code WorkflowTaskServiceImpl},
 * which unconditionally depends on {@code DocumentSignatureService}) can wire
 * up normally without the whole application failing to start; every
 * operation refuses cleanly with 503 SIGNATURE_FEATURE_DISABLED rather than
 * the endpoint not existing at all - the one place this feature's "off means
 * 404" convention (see watermark's own) isn't quite exact, because
 * sign-and-approve shares {@code WorkflowTaskController} with always-on
 * endpoints rather than living behind its own conditional controller.
 */
public class NullDocumentSigner implements DocumentSigner {

    @Override
    public SigningBackend backend() {
        return SigningBackend.local_dev;
    }

    @Override
    public String ensureKey(String keyName) {
        throw disabled();
    }

    @Override
    public byte[] sign(String keyName, byte[] sha256Digest) {
        throw disabled();
    }

    private static SignatureException disabled() {
        return SignatureException.unavailable("SIGNATURE_FEATURE_DISABLED",
                "Digital signatures are not enabled for this deployment.");
    }
}
