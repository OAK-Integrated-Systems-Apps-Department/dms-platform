package com.oak.dms.scanning.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;

/**
 * Cuts one stream into consecutive segments of at most {@code segmentBytes},
 * each starting with the last {@code overlapBytes} of the one before - so a
 * signature that straddles a cut is still whole in the next segment. Lets a
 * file larger than clamd scans in one go (2147483645 bytes) be scanned in
 * full, one segment per {@code INSTREAM}, in a single pass over the source:
 * only the overlap is held in memory, never a segment.
 * <p>
 * Use: {@code while ((segment = segments.next()) != null) scan(segment);}.
 * Each segment must be read to its end before {@link #next()} is called
 * again. Doesn't close the source. Package-private - {@link ClamAvScanAdapter}
 * is its only user.
 */
class OverlappingSegments {

    private final PushbackInputStream source;
    private final long segmentBytes;
    private final byte[] tail;
    private int tailLength;
    private boolean first = true;

    OverlappingSegments(InputStream source, long segmentBytes, int overlapBytes) {
        if (overlapBytes < 0 || overlapBytes >= segmentBytes) {
            throw new IllegalArgumentException("Overlap must be at least 0 and smaller than a segment, got "
                    + overlapBytes + " of " + segmentBytes);
        }
        this.source = new PushbackInputStream(source, 1);
        this.segmentBytes = segmentBytes;
        this.tail = new byte[overlapBytes];
    }

    /** The next segment, or null once the source is used up (an empty source still gives one, empty, segment). */
    InputStream next() throws IOException {
        if (!first) {
            int b = source.read();
            if (b == -1) {
                return null;
            }
            source.unread(b);
        }
        first = false;
        return new Segment();
    }

    /** The retained overlap first, then fresh bytes from the source up to {@code segmentBytes} in all. */
    private final class Segment extends InputStream {
        private final byte[] replay;
        private int replayed;
        private long remainingFresh;

        private Segment() {
            replay = Arrays.copyOf(tail, tailLength);
            remainingFresh = segmentBytes - replay.length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (replayed < replay.length) {
                int n = Math.min(len, replay.length - replayed);
                System.arraycopy(replay, replayed, b, off, n);
                replayed += n;
                remember(b, off, n);
                return n;
            }
            if (remainingFresh <= 0) {
                return -1;
            }
            int n = source.read(b, off, (int) Math.min(len, remainingFresh));
            if (n > 0) {
                remainingFresh -= n;
                remember(b, off, n);
            }
            return n;
        }
    }

    /** Keeps the last {@code tail.length} bytes handed out - the start of the next segment. */
    private void remember(byte[] b, int off, int n) {
        if (tail.length == 0) {
            return;
        }
        if (n >= tail.length) {
            System.arraycopy(b, off + n - tail.length, tail, 0, tail.length);
            tailLength = tail.length;
            return;
        }
        int keep = Math.min(tailLength, tail.length - n);
        System.arraycopy(tail, tailLength - keep, tail, 0, keep);
        System.arraycopy(b, off, tail, keep, n);
        tailLength = keep + n;
    }
}
