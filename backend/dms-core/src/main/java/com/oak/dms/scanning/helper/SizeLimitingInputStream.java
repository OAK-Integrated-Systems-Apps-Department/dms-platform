package com.oak.dms.scanning.helper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Enforces the scan engine's {@code max_file_size_bytes} on the bytes that
 * actually arrive, not the declared Content-Length. Throws an
 * {@link IOException} once the limit is crossed (so the storage adapter
 * aborts the staging upload); the gate then checks {@link #limitExceeded()}
 * to tell that apart from a genuine storage failure, since each adapter
 * wraps whatever it catches in its own {@code StorageUploadException}.
 */
public class SizeLimitingInputStream extends FilterInputStream {

    private final long maxBytes;
    private long count;
    private boolean exceeded;

    public SizeLimitingInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    public boolean limitExceeded() {
        return exceeded;
    }

    public long count() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        return 0;
    }

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

    private void add(long n) throws IOException {
        count += n;
        if (count > maxBytes) {
            exceeded = true;
            throw new IOException("Upload exceeds the maximum size of " + maxBytes + " bytes");
        }
    }
}
