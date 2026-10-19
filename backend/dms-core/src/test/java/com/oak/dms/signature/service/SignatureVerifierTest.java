package com.oak.dms.signature.service;

import com.oak.dms.signature.helper.PemKeys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification uses ONLY the stored public key (decision 5) - no signer
 * backend involved, so this is tested with a plain, locally generated
 * key pair rather than mocking OpenBao.
 */
class SignatureVerifierTest {

    private final SignatureVerifier verifier = new SignatureVerifier();

    private KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private String sign(KeyPair pair, String text) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    @Test
    void verifiesAGenuineSignature() throws Exception {
        KeyPair pair = ecKeyPair();
        String manifest = "{\"documentId\":\"doc-1\"}";
        String signature = sign(pair, manifest);
        String pem = PemKeys.encodePublicKey(pair.getPublic());

        assertThat(verifier.verify(manifest, signature, pem)).isTrue();
    }

    @Test
    void rejectsATamperedManifest() throws Exception {
        KeyPair pair = ecKeyPair();
        String signature = sign(pair, "{\"documentId\":\"doc-1\"}");
        String pem = PemKeys.encodePublicKey(pair.getPublic());

        assertThat(verifier.verify("{\"documentId\":\"doc-2\"}", signature, pem)).isFalse();
    }

    @Test
    void rejectsTheWrongPublicKey() throws Exception {
        KeyPair signer = ecKeyPair();
        KeyPair otherKey = ecKeyPair();
        String manifest = "{\"documentId\":\"doc-1\"}";
        String signature = sign(signer, manifest);
        String wrongPem = PemKeys.encodePublicKey(otherKey.getPublic());

        assertThat(verifier.verify(manifest, signature, wrongPem)).isFalse();
    }

    @Test
    void rejectsGarbageSignatureBytesWithoutThrowing() throws Exception {
        KeyPair pair = ecKeyPair();
        String pem = PemKeys.encodePublicKey(pair.getPublic());

        assertThat(verifier.verify("{\"documentId\":\"doc-1\"}", "not-valid-base64!!!", pem)).isFalse();
        assertThat(verifier.verify("{\"documentId\":\"doc-1\"}", Base64.getEncoder().encodeToString("garbage".getBytes()), pem))
                .isFalse();
    }

    @Test
    void rejectsGarbagePublicKeyPemWithoutThrowing() throws Exception {
        KeyPair pair = ecKeyPair();
        String signature = sign(pair, "{\"documentId\":\"doc-1\"}");

        assertThat(verifier.verify("{\"documentId\":\"doc-1\"}", signature, "-----BEGIN PUBLIC KEY-----\nnot valid\n-----END PUBLIC KEY-----\n"))
                .isFalse();
    }
}
