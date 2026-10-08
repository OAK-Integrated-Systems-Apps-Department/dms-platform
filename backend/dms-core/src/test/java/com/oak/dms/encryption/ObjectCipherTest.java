package com.oak.dms.encryption;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stored-object cipher: round trips, exact sizes, and that tampering is caught rather than decrypted. */
class ObjectCipherTest {

    private static final int SEGMENT = 4096; // small, so the tests cross many segment boundaries
    private final ObjectCipher cipher = new ObjectCipher(SEGMENT);
    private final byte[] key = random(32);
    private final byte[] aad = ObjectCipher.associatedData("loc-1", "docs/a.pdf");

    @Test
    void roundTrips_atEverySizeAroundSegmentBoundaries_andTheSizeIsKnownUpFront() throws IOException {
        for (int size : new int[]{0, 1, 100, SEGMENT - 40, SEGMENT, SEGMENT + 1, 3 * SEGMENT + 17, 200_000}) {
            byte[] plaintext = random(size);
            byte[] ciphertext = encrypt(plaintext);

            assertThat(ciphertext.length).as("size %d", size).isEqualTo(cipher.ciphertextSize(key, size));
            assertThat(decrypt(ciphertext, key, aad)).as("size %d", size).isEqualTo(plaintext);
        }
    }

    @Test
    void theStoredBytes_neverContainThePlaintext() throws IOException {
        byte[] plaintext = "CONFIDENTIAL salary list - Jane 120000".repeat(50).getBytes(StandardCharsets.UTF_8);
        String stored = new String(encrypt(plaintext), StandardCharsets.ISO_8859_1);

        assertThat(stored).doesNotContain("CONFIDENTIAL").doesNotContain("salary");
    }

    @Test
    void aModifiedTruncatedOrMisplacedObject_failsInsteadOfDecrypting() throws IOException {
        byte[] ciphertext = encrypt(random(3 * SEGMENT));

        byte[] flipped = ciphertext.clone();
        flipped[flipped.length / 2] ^= 1;
        assertThatThrownBy(() -> decrypt(flipped, key, aad)).isInstanceOf(IOException.class);

        byte[] truncated = Arrays.copyOf(ciphertext, ciphertext.length - SEGMENT);
        assertThatThrownBy(() -> decrypt(truncated, key, aad)).isInstanceOf(IOException.class);

        // The same bytes under another object's key, or with another data key, don't decrypt either.
        assertThatThrownBy(() -> decrypt(ciphertext, key, ObjectCipher.associatedData("loc-1", "docs/b.pdf")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decrypt(ciphertext, random(32), aad)).isInstanceOf(IOException.class);
    }

    private byte[] encrypt(byte[] plaintext) throws IOException {
        try (InputStream in = cipher.encrypting(new ByteArrayInputStream(plaintext), key, aad)) {
            return in.readAllBytes();
        }
    }

    private byte[] decrypt(byte[] ciphertext, byte[] dataKey, byte[] associatedData) throws IOException {
        try (InputStream in = cipher.decrypting(new ByteArrayInputStream(ciphertext), dataKey, associatedData)) {
            return in.readAllBytes();
        }
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
