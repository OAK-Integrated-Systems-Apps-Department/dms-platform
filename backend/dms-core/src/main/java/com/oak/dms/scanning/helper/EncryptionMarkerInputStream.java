package com.oak.dms.scanning.helper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Watches the bytes of a PDF as they stream into staging for the
 * {@code /Encrypt} name - present in the trailer (or cross-reference stream
 * dictionary) of every encrypted PDF - so encryption is detected in the same
 * single pass that stages and hashes the file, without running a PDF parser
 * over bytes that haven't been scanned yet.
 * <p>
 * Any encrypted PDF counts as unscannable, including owner-password-only
 * files that open without a password (an agreed Phase 1 policy: no PDFBox,
 * no parser on unscanned input). Dictionaries are never compressed, so the
 * name always appears literally.
 */
public class EncryptionMarkerInputStream extends FilterInputStream {

    private static final byte[] MARKER = "/Encrypt".getBytes(StandardCharsets.US_ASCII);

    private int matched;
    private boolean found;

    public EncryptionMarkerInputStream(InputStream in) {
        super(in);
    }

    public boolean markerFound() {
        return found;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            observe((byte) b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        for (int i = 0; i < n && !found; i++) {
            observe(b[off + i]);
        }
        return n;
    }

    /** Never skip - every byte has to pass the marker check. */
    @Override
    public long skip(long n) throws IOException {
        return 0;
    }

    /** Explicit loop, so no JDK shortcut can ever bypass {@link #read(byte[], int, int)}. */
    @Override
    public long transferTo(OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }

    private void observe(byte b) {
        if (found) {
            return;
        }
        if (b == MARKER[matched]) {
            matched++;
            if (matched == MARKER.length) {
                found = true;
            }
        } else {
            // '/' never recurs inside the marker, so restarting is exact.
            matched = b == MARKER[0] ? 1 : 0;
        }
    }
}
