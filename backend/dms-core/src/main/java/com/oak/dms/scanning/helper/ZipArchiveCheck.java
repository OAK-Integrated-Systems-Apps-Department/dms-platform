package com.oak.dms.scanning.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Checks that an uploaded {@code .zip} is a complete, readable ZIP archive the virus scanner can
 * inspect in full - before it is scanned or becomes a document. The staged copy is written to a
 * private temporary file and opened by its central directory - the index at the end of every ZIP -
 * the way unzip tools read it, so every standard layout is understood: ZIP64, an archive comment,
 * and entries whose sizes follow their data (how OneDrive, SharePoint and Google Drive build the
 * ZIPs they download). Every entry is then read once, its CRC compared with the one recorded, and
 * the bytes thrown away. <b>Nothing is extracted and nothing in it is ever run or opened</b>; the
 * temporary copy is deleted straight after, and the archive is stored exactly as uploaded.
 * <p>
 * Refused: a damaged or truncated archive (no readable index, data that doesn't match its CRC), an
 * empty one, more than {@code maxEntries} entries or more than {@code maxUncompressedBytes} once
 * unpacked - ClamAV's own limits, past which it would stop looking and pass the rest unscanned -
 * and entries compressed some way other than stored/deflated, whose content can't be checked.
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

    private ZipArchiveCheck() {
    }

    /** Whether a ZIP-based file's index lists {@code name}; read the same way, nothing extracted. */
    public enum Presence { PRESENT, ABSENT, UNREADABLE }

    public static Result check(InputStream content, int maxEntries, long maxUncompressedBytes) {
        return onTempCopy(content, copy -> check(copy, maxEntries, maxUncompressedBytes));
    }

    /**
     * Looks {@code name} up in the archive's central directory - how an Office Open XML package is
     * confirmed whatever order its parts were written in. {@code UNREADABLE} when the index itself
     * can't be read; the caller decides what that means for its format.
     */
    public static Presence containsEntry(InputStream content, String name) {
        return onTempCopy(content, copy -> {
            try (ZipFile zip = new ZipFile(copy.toFile())) {
                return zip.getEntry(name) != null ? Presence.PRESENT : Presence.ABSENT;
            } catch (IOException | IllegalArgumentException e) {
                return Presence.UNREADABLE;
            }
        });
    }

    private static <T> T onTempCopy(InputStream content, java.util.function.Function<Path, T> work) {
        Path copy = null;
        try {
            copy = Files.createTempFile("dms-zip-check-", ".zip");
            Files.copy(content, copy, StandardCopyOption.REPLACE_EXISTING);
            return work.apply(copy);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the staged ZIP for checking", e);
        } finally {
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException ignored) {
                    // the OS cleans up its temp directory
                }
            }
        }
    }

    private static Result check(Path file, int maxEntries, long maxUncompressedBytes) {
        int entries = 0;
        long unpacked = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() > maxEntries) {
                return invalid("This ZIP holds more than " + maxEntries + " files - the most the virus scanner "
                        + "checks in one archive. Split it into smaller archives.", zip.size(), 0);
            }
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                ZipEntry entry = all.nextElement();
                entries++;
                if (entry.isDirectory()) {
                    continue;
                }
                CRC32 crc = new CRC32();
                try (InputStream data = zip.getInputStream(entry)) {
                    for (int n = data.read(buffer); n >= 0; n = data.read(buffer)) {
                        unpacked += n;
                        if (unpacked > maxUncompressedBytes) {
                            return invalid("Unpacked, this ZIP comes to more than "
                                    + (maxUncompressedBytes / (1024 * 1024)) + " MB - the most the virus scanner "
                                    + "checks in one archive. Split it into smaller archives.", entries, unpacked);
                        }
                        crc.update(buffer, 0, n);
                    }
                }
                if (entry.getCrc() != -1 && crc.getValue() != entry.getCrc()) {
                    return invalid(damaged(), entries, unpacked);
                }
            }
            if (entries == 0) {
                return invalid("This ZIP is empty.", 0, 0);
            }
            return new Result(Outcome.VALID, null, entries, unpacked);
        } catch (ZipException e) {
            String reason = e.getMessage() == null ? "" : e.getMessage();
            if (reason.contains("encrypted")) {
                return new Result(Outcome.ENCRYPTED, "Password-protected ZIP archive", entries, unpacked);
            }
            if (reason.contains("compression method")) {
                return invalid("This ZIP uses a compression method that can't be checked (only the standard "
                        + "Deflate or no compression can). Re-create it with a standard ZIP tool and upload it "
                        + "again.", entries, unpacked);
            }
            return invalid(damaged(), entries, unpacked);
        } catch (IOException | IllegalArgumentException e) {
            // EOF part-way through an entry, a header or entry name that doesn't parse.
            return invalid(damaged(), entries, unpacked);
        }
    }

    private static String damaged() {
        return "This ZIP file is damaged or incomplete, so it can't be uploaded. Re-create it and upload it again.";
    }

    private static Result invalid(String message, int entries, long unpacked) {
        return new Result(Outcome.INVALID, message, entries, unpacked);
    }
}
