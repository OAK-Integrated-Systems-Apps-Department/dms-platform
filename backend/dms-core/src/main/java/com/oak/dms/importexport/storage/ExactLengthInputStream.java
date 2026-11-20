package com.oak.dms.importexport.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Hands a storage adapter exactly the number of bytes a caller said it was sending, no more, and counts them (DOC-10).
 * <p>
 * The declared length of a bulk-import file is not trustworthy: for an entry of an uploaded ZIP it is a field the
 * uploader wrote, and the JDK does not enforce it while inflating, so an entry that claims 100 bytes can hold gigabytes.
 * The storage adapters do not enforce it either - the file-system adapter reads to the end of the stream whatever the
 * length, the S3 and Azure adapters and the encryption wrapper echo the declared length back as the stored size - so the
 * only reliable measure is to count what is really read.
 * <p>
 * This stream never delivers more than {@code expectedBytes}: at that count it reports end-of-stream, so the adapter
 * cannot store more than was declared (and the batch budget, which was checked against the declared length, cannot be
 * exceeded). It cannot know by itself whether the source had more, or ended early - an adapter that stops reading at the
 * declared length never asks - so after the upload the caller asks {@link #isExactlyConsumed()}: exactly the expected
 * number of bytes were read <b>and</b> the source has nothing left.
 * <p>
 * Not markable: the count must stay true.
 */
public final class ExactLengthInputStream extends FilterInputStream {

    private final long expectedBytes;
    private long count;
    private boolean sourceEndedEarly;

    public ExactLengthInputStream(InputStream source, long expectedBytes) {
        super(source);
        if (expectedBytes < 0) {
            throw new IllegalArgumentException("expectedBytes must not be negative: " + expectedBytes);
        }
        this.expectedBytes = expectedBytes;
    }

    /** Bytes handed out so far - the real size of what was read, never more than the expected length. */
    public long count() {
        return count;
    }

    /** The source ended before the expected number of bytes. */
    public boolean sourceEndedEarly() {
        return sourceEndedEarly;
    }

    /**
     * True only if exactly the expected number of bytes were read and the source holds nothing more. Reads one byte
     * from the source to find out, so call it once the consumer is done. False for a source that ended early, was
     * not read to the expected length (a truncated object), or holds more than it declared.
     */
    public boolean isExactlyConsumed() throws IOException {
        return count == expectedBytes && in.read() == -1;
    }

    @Override
    public int read() throws IOException {
        if (count >= expectedBytes) {
            return -1;
        }
        int b = in.read();
        if (b < 0) {
            sourceEndedEarly = true;
            return -1;
        }
        count++;
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long left = expectedBytes - count;
        if (left <= 0) {
            return -1;
        }
        int n = in.read(buffer, offset, (int) Math.min(length, left));
        if (n < 0) {
            sourceEndedEarly = true;
            return -1;
        }
        count += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(Math.max(0, Math.min(n, expectedBytes - count)));
        count += Math.max(0, skipped);
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), expectedBytes - count);
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readLimit) {
        // not supported - see markSupported()
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }
}
