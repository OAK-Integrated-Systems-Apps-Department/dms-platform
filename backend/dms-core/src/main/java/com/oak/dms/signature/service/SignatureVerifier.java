package com.oak.dms.signature.service;

import com.oak.dms.signature.helper.PemKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Verifies a signature against ONLY the public key stored on its own
 * {@code document_signatures} row (plain JCA {@code SHA256withECDSA}) - no
 * OpenBao round trip, ever (decision 5). This is what makes a signature's
 * evidence independently checkable forever, even after its key is revoked
 * or the signer backend is unreachable.
 * <p>
 * Every failure mode - a tampered manifest, the wrong public key, or simply
 * garbage input (a corrupted base64 string, a PEM that isn't an EC key) -
 * collapses to a plain {@code false}: this class never throws for bad input,
 * since "does this verify" must be a safe question to ask of anything on
 * file, and a malformed row is itself evidence of tampering, not a bug to
 * propagate as a 500.
 */
@Slf4j
@Component
public class SignatureVerifier {

    public boolean verify(String manifestText, String signatureDerBase64, String publicKeyPem) {
        try {
            PublicKey publicKey = PemKeys.decodePublicKey(publicKeyPem);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureDerBase64);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(manifestText.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.debug("Signature did not verify: {}", e.getMessage());
            return false;
        }
    }
}
