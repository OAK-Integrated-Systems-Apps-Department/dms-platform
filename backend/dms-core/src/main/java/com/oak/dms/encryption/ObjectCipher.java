package com.oak.dms.encryption;

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The cipher for stored objects: Google Tink's streaming AEAD - AES-256-GCM
 * with an HKDF-SHA256 subkey per object and authenticated segments - so a
 * modified, truncated or reordered object fails to decrypt instead of
 * yielding wrong bytes. The associated data binds a ciphertext to its
 * storage location and object key, so one object's bytes can't be swapped
 * in for another's.
 */
public final class ObjectCipher {

    public static final String ALGORITHM = "AES256_GCM_HKDF_STREAMING";
    private static final int KEY_BYTES = 32;

    private final int segmentSize;

    public ObjectCipher(int segmentSize) {
        this.segmentSize = segmentSize;
    }

    public static byte[] associatedData(String storageLocationId, String objectKey) {
        return ("dms:" + storageLocationId + ":" + objectKey).getBytes(StandardCharsets.UTF_8);
    }

    public long ciphertextSize(byte[] dataKey, long plaintextSize) {
        return streaming(dataKey).expectedCiphertextSize(plaintextSize);
    }

    /** Ciphertext of {@code plaintext}, produced as it's read - never more than a segment held in memory. */
    public InputStream encrypting(InputStream plaintext, byte[] dataKey, byte[] associatedData) {
        return new EncryptingInputStream(plaintext, streaming(dataKey), associatedData);
    }

    public InputStream decrypting(InputStream ciphertext, byte[] dataKey, byte[] associatedData) {
        try {
            return streaming(dataKey).newDecryptingStream(ciphertext, associatedData);
        } catch (GeneralSecurityException | IOException e) {
            throw new KeyEncryptionService.KeyServiceException("Could not start decrypting the object", e);
        }
    }

    private AesGcmHkdfStreaming streaming(byte[] dataKey) {
        try {
            return new AesGcmHkdfStreaming(dataKey, "HmacSha256", KEY_BYTES, segmentSize, 0);
        } catch (GeneralSecurityException e) {
            throw new KeyEncryptionService.KeyServiceException("Invalid data key", e);
        }
    }

    /**
     * Pull-based encryption: each read encrypts just enough plaintext to have
     * output to return, so an upload stays streaming on the caller's thread.
     */
    private static final class EncryptingInputStream extends InputStream {

        private final InputStream plaintext;
        private final Buffer buffer = new Buffer();
        private final OutputStream encrypter;
        private final byte[] chunk = new byte[64 * 1024];
        private int position;
        private boolean finished;

        EncryptingInputStream(InputStream plaintext, AesGcmHkdfStreaming streaming, byte[] associatedData) {
            this.plaintext = plaintext;
            try {
                this.encrypter = streaming.newEncryptingStream(buffer, associatedData);
            } catch (GeneralSecurityException | IOException e) {
                throw new KeyEncryptionService.KeyServiceException("Could not start encrypting the object", e);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] out, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (position >= buffer.size()) {
                if (finished) {
                    return -1;
                }
                buffer.reset();
                position = 0;
                int n = plaintext.read(chunk);
                if (n < 0) {
                    encrypter.close(); // writes the final, specially-tagged segment
                    finished = true;
                } else {
                    encrypter.write(chunk, 0, n);
                }
            }
            int n = Math.min(length, buffer.size() - position);
            System.arraycopy(buffer.bytes(), position, out, offset, n);
            position += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            plaintext.close();
        }

        private static final class Buffer extends ByteArrayOutputStream {
            byte[] bytes() {
                return buf;
            }
        }
    }
}
