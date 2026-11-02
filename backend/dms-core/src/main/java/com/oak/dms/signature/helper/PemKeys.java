package com.oak.dms.signature.helper;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** PEM encode/decode for an EC public key (SubjectPublicKeyInfo, X.509) - no private-key handling here. */
public final class PemKeys {

    private static final String HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String FOOTER = "-----END PUBLIC KEY-----";

    private PemKeys() {
    }

    public static String encodePublicKey(PublicKey key) {
        String base64 = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder pem = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return pem.append(FOOTER).append('\n').toString();
    }

    public static PublicKey decodePublicKey(String pem) {
        String base64 = pem.replace(HEADER, "").replace(FOOTER, "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Not a valid EC public key PEM", e);
        }
    }
}
