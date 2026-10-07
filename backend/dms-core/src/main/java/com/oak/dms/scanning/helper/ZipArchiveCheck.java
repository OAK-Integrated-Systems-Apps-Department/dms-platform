package com.oak.dms.scanning.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

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
 * like any other encrypted upload (unscannable). Nested archives are scanned by ClamAV, which opens
 * them itself and flags anything it can't finish (clamd {@code AlertExceedsMax},
 * {@code AlertEncryptedArchive}).
 * <p>
 * Also refused: a ZIP holding a program or script ({@link #DEFAULT_BLOCKED_EXTENSIONS}, or bytes
 * that start like one), even when ClamAV finds no malware in it - in ZIPs nested inside it too, up
 * to {@link #MAX_NESTING} deep. Not caught: an installer renamed to a document extension whose
 * content is a generic container (an .msi is an OLE2 file, like a .doc) - ClamAV still scans it.
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

    /**
     * Programs and scripts - refused inside a ZIP even when ClamAV finds nothing in them: a DMS
     * stores documents, and a clean scan only means no <em>known</em> malware. Matched on the
     * entry's extension (case-insensitive); {@link #looksExecutable} also catches them renamed.
     */
    public static final Set<String> DEFAULT_BLOCKED_EXTENSIONS = Set.of(
            // Windows programs, installers, libraries, drivers and shortcuts
            "exe", "msi", "msp", "mst", "msix", "msixbundle", "appx", "appxbundle", "dll", "sys", "drv",
            "ocx", "cpl", "scr", "com", "pif", "lnk", "url", "application", "gadget", "hta", "chm",
            "reg", "inf",
            // Windows scripts
            "bat", "cmd", "ps1", "psm1", "psd1", "ps1xml", "vbs", "vbe", "js", "jse", "wsf", "wsh", "wsc",
            "sct", "msc",
            // Java, other platforms' programs and installers, shell scripts
            "jar", "class", "apk", "dex", "app", "dmg", "pkg", "deb", "rpm", "run", "elf", "sh",
            "bash", "zsh", "csh", "ksh", "command",
            // Other script languages that run when opened
            "py", "pyc", "pyw", "pl", "rb", "php", "jsp", "asp", "aspx");

    public static Result check(InputStream content, int maxEntries, long maxUncompressedBytes) {
        return check(content, maxEntries, maxUncompressedBytes, DEFAULT_BLOCKED_EXTENSIONS);
    }

    /** As {@link #check(InputStream, int, long)}, refusing entries with any of {@code blockedExtensions}. */
    public static Result check(InputStream content, int maxEntries, long maxUncompressedBytes,
                               Set<String> blockedExtensions) {
        return onTempCopy(content, copy -> check(copy, maxEntries, maxUncompressedBytes, blockedExtensions));
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

    private static Result check(Path file, int maxEntries, long maxUncompressedBytes, Set<String> blockedExtensions) {
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
                if (blockedExtensions.contains(extension(entry.getName()))) {
                    return invalid(program(entry.getName()), entries, unpacked);
                }
                CRC32 crc = new CRC32();
                byte[] head = new byte[HEAD_BYTES];
                int headLength = 0;
                try (InputStream data = zip.getInputStream(entry)) {
                    for (int n = data.read(buffer); n >= 0; n = data.read(buffer)) {
                        unpacked += n;
                        if (unpacked > maxUncompressedBytes) {
                            return invalid("Unpacked, this ZIP comes to more than "
                                    + (maxUncompressedBytes / (1024 * 1024)) + " MB - the most the virus scanner "
                                    + "checks in one archive. Split it into smaller archives.", entries, unpacked);
                        }
                        crc.update(buffer, 0, n);
                        if (headLength < head.length) {
                            int take = Math.min(n, head.length - headLength);
                            System.arraycopy(buffer, 0, head, headLength, take);
                            headLength += take;
                        }
                    }
                }
                if (entry.getCrc() != -1 && crc.getValue() != entry.getCrc()) {
                    return invalid(damaged(), entries, unpacked);
                }
                if (looksExecutable(head, headLength)) {
                    return invalid(program(entry.getName()), entries, unpacked);
                }
                if (isNestedZip(entry.getName(), head, headLength)) {
                    try (InputStream nested = zip.getInputStream(entry)) {
                        String blocked = blockedInside(nested, blockedExtensions, 1);
                        if (blocked != null) {
                            return invalid(program(entry.getName() + " > " + blocked), entries, unpacked);
                        }
                    }
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

    /** Enough of an entry's start to recognise a program by its header. */
    private static final int HEAD_BYTES = 64;
    /** How deep ZIPs inside the ZIP are looked into; anything deeper is left to ClamAV. */
    private static final int MAX_NESTING = 3;
    /** ZIP-based document formats - not looked into as nested archives. */
    private static final Set<String> ZIP_BASED_DOCUMENTS = Set.of(
            "docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm", "potx", "ppsx", "vsdx",
            "odt", "ods", "odp", "odg", "ott", "ots", "otp", "epub");

    /**
     * Whether the bytes start like a program, whatever the file is called: a Windows program or
     * library ("MZ"), a Linux one (ELF), a macOS one (Mach-O, or a universal binary - whose magic a
     * Java class file shares), a Windows shortcut, or a script with a {@code #!} line.
     */
    static boolean looksExecutable(byte[] head, int length) {
        if (length >= 64 && head[0] == 'M' && head[1] == 'Z') {
            // A real DOS/PE header points (at 0x3C) to its PE header a little way in; text that
            // merely starts with "MZ" has ASCII there, which reads as a huge offset.
            long peOffset = (head[0x3c] & 0xffL) | (head[0x3d] & 0xffL) << 8 | (head[0x3e] & 0xffL) << 16
                    | (head[0x3f] & 0xffL) << 24;
            if (peOffset >= 0x40 && peOffset < 0x10000) {
                return true;
            }
        }
        if (length >= 2 && head[0] == '#' && head[1] == '!') {
            return true;
        }
        if (length < 4) {
            return false;
        }
        int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
        return magic == 0x7f454c46 // ELF
                || magic == 0xfeedface || magic == 0xfeedfacf || magic == 0xcefaedfe || magic == 0xcffaedfe // Mach-O
                || magic == 0xcafebabe // Mach-O universal binary / Java class
                || magic == 0x4c000000; // Windows shortcut (.lnk header size 0x4C)
    }

    private static boolean isNestedZip(String name, byte[] head, int length) {
        boolean zipHeader = length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        return zipHeader && !ZIP_BASED_DOCUMENTS.contains(extension(name));
    }

    /**
     * The first program or script inside a nested ZIP (as "inner.zip > setup.msi" when deeper), or
     * null. Read front to back as a stream - its index isn't at hand inside another archive. A nested
     * archive that can't be read this way is left to ClamAV, which opens nested archives itself.
     */
    private static String blockedInside(InputStream content, Set<String> blockedExtensions, int depth) {
        try {
            ZipInputStream zip = new ZipInputStream(content);
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (blockedExtensions.contains(extension(entry.getName()))) {
                    return entry.getName();
                }
                byte[] head = zip.readNBytes(HEAD_BYTES);
                if (looksExecutable(head, head.length)) {
                    return entry.getName();
                }
                if (depth < MAX_NESTING && isNestedZip(entry.getName(), head, head.length)) {
                    InputStream rest = new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(head),
                            new java.io.FilterInputStream(zip) {
                                @Override
                                public void close() {
                                    // the outer entry's stream stays open for the next entry
                                }
                            });
                    String blocked = blockedInside(rest, blockedExtensions, depth + 1);
                    if (blocked != null) {
                        return entry.getName() + " > " + blocked;
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static String extension(String entryName) {
        String name = entryName.substring(entryName.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String program(String entryName) {
        return "This ZIP contains a program or script (" + entryName + "), which can't be uploaded. Remove it "
                + "and upload the ZIP again.";
    }

    private static String damaged() {
        return "This ZIP file is damaged or incomplete, so it can't be uploaded. Re-create it and upload it again.";
    }

    private static Result invalid(String message, int entries, long unpacked) {
        return new Result(Outcome.INVALID, message, entries, unpacked);
    }
}
