package com.oak.dms.scanning.helper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Checks that an uploaded {@code .zip} is a complete, readable ZIP archive the virus scanner can
 * inspect in full - before it is scanned or becomes a document. One streaming pass over the
 * staged copy: every entry is read (and its CRC checked) by the JDK's own ZIP reader and the
 * bytes thrown away. <b>Nothing is extracted to disk and nothing in it is ever run or
 * opened</b>; the archive is stored exactly as uploaded.
 * <p>
 * Refused: a damaged or truncated archive (bad header, bad CRC, no end-of-archive record), an
 * empty one, more than {@code maxEntries} entries or more than {@code maxUncompressedBytes} once
 * unpacked - ClamAV's own limits, past which it would stop looking and pass the rest unscanned.
 * Password-protected entries can't be scanned at all, so they're reported separately and handled
 * like any other encrypted upload (unscannable). Nested archives are left to ClamAV, which opens
 * them itself and flags anything it can't finish (clamd {@code AlertExceedsMax},
 * {@code AlertEncryptedArchive}).
 */
public final class ZipArchiveCheck {

    /** What the check found; {@code message} is shown to the uploader. */
    public enum Outcome { VALID, ENCRYPTED, INVALID }

    public record Result(Outcome outcome, String message, int entries, long uncompressedBytes) {
    }

    /** End of central directory record (22 bytes) - the last thing in every ZIP, followed only by an optional comment. */
    private static final byte[] EOCD = {'P', 'K', 5, 6};
    /** The EOCD record (22 bytes) plus the longest comment it may carry. */
    private static final int TAIL_BYTES = 22 + 0xFFFF;

    private ZipArchiveCheck() {
    }

    public static Result check(InputStream content, int maxEntries, long maxUncompressedBytes) {
        TailKeepingInputStream source = new TailKeepingInputStream(content, TAIL_BYTES);
        int entries = 0;
        long unpacked = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(source)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > maxEntries) {
                    return invalid("This ZIP holds more than " + maxEntries + " files - the most the virus scanner "
                            + "checks in one archive. Split it into smaller archives.", entries, unpacked);
                }
                for (int n = zip.read(buffer); n >= 0; n = zip.read(buffer)) {
                    unpacked += n;
                    if (unpacked > maxUncompressedBytes) {
                        return invalid("Unpacked, this ZIP comes to more than " + (maxUncompressedBytes / (1024 * 1024))
                                + " MB - the most the virus scanner checks in one archive. Split it into smaller "
                                + "archives.", entries, unpacked);
                    }
                }
            }
            if (entries == 0) {
                return invalid("This ZIP is empty.", 0, 0);
            }
            source.transferTo(OutputStream.nullOutputStream()); // the central directory and end record
            if (!source.endsWithEndRecordFor(entries)) {
                return invalid(damaged(), entries, unpacked);
            }
            return new Result(Outcome.VALID, null, entries, unpacked);
        } catch (ZipException e) {
            String reason = e.getMessage() == null ? "" : e.getMessage();
            if (reason.contains("encrypted")) {
                return new Result(Outcome.ENCRYPTED, "Password-protected ZIP archive", entries, unpacked);
            }
            if (reason.contains("EXT descriptor")) {
                return invalid("This ZIP stores files in a way that can't be checked (uncompressed entries with "
                        + "trailing descriptors). Re-create it with a standard ZIP tool and upload it again.",
                        entries, unpacked);
            }
            return invalid(damaged(), entries, unpacked);
        } catch (IOException e) {
            // EOF part-way through an entry, a header that doesn't parse - a damaged or cut-off archive.
            return invalid(damaged(), entries, unpacked);
        } catch (IllegalArgumentException e) {
            // The JDK's reader rejects malformed entry names this way.
            return invalid(damaged(), entries, unpacked);
        }
    }

    private static String damaged() {
        return "This ZIP file is damaged or incomplete, so it can't be uploaded. Re-create it and upload it again.";
    }

    private static Result invalid(String message, int entries, long unpacked) {
        return new Result(Outcome.INVALID, message, entries, unpacked);
    }

    /** Passes bytes through, remembering the last {@code capacity} of them. */
    private static final class TailKeepingInputStream extends FilterInputStream {
        private final byte[] ring;
        private long total;

        TailKeepingInputStream(InputStream in, int capacity) {
            super(in);
            this.ring = new byte[capacity];
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                keep((byte) b);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            for (int i = 0; i < n; i++) {
                keep(b[off + i]);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            // Read rather than skip, so skipped bytes are still seen.
            long skipped = 0;
            byte[] scratch = new byte[8192];
            while (skipped < n) {
                int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
                if (r < 0) {
                    break;
                }
                skipped += r;
            }
            return skipped;
        }

        private void keep(byte b) {
            ring[(int) (total % ring.length)] = b;
            total++;
        }

        /**
         * Whether the archive ends with a complete end-of-central-directory record: its comment runs
         * exactly to the end of the file, and the entry count it records is the number read (0xFFFF
         * means a ZIP64 archive, whose count lives elsewhere).
         */
        boolean endsWithEndRecordFor(int entries) {
            int length = (int) Math.min(total, ring.length);
            byte[] tail = new byte[length];
            long start = total - length;
            for (int i = 0; i < length; i++) {
                tail[i] = ring[(int) ((start + i) % ring.length)];
            }
            for (int i = length - 22; i >= 0; i--) {
                if (tail[i] != EOCD[0] || tail[i + 1] != EOCD[1] || tail[i + 2] != EOCD[2] || tail[i + 3] != EOCD[3]) {
                    continue;
                }
                int recorded = (tail[i + 10] & 0xFF) | (tail[i + 11] & 0xFF) << 8;
                int comment = (tail[i + 20] & 0xFF) | (tail[i + 21] & 0xFF) << 8;
                if (i + 22 + comment == length) {
                    return recorded == 0xFFFF || recorded == entries;
                }
            }
            return false;
        }
    }
}
