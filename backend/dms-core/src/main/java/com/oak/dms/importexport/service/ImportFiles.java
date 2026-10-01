package com.oak.dms.importexport.service;

import com.oak.dms.importexport.exception.BulkImportException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The files a batch's rows refer to, looked up by the template's filename
 * cell. A batch's staged files are its ZIP ({@code archive.zip}) and/or its
 * loose files ({@code files/}); a resubmitted batch also falls back to the
 * original batch's files, so fixing a failed row needs no second upload.
 * <p>
 * A reference matches, in order: a loose file of that name; a ZIP entry at
 * exactly that path; the only ZIP entry with that name in any folder. Each
 * step is tried as typed, then ignoring case. Entry names are only ever
 * used as lookup keys - nothing is extracted to disk under them.
 */
public final class ImportFiles implements Closeable {

    public static final String ARCHIVE = "archive.zip";
    public static final String LOOSE_DIR = "files";

    /** A resolved file: open() streams it fresh each time. */
    public interface ImportFile {
        String name();

        long size();

        InputStream open() throws IOException;
    }

    public sealed interface Resolution permits Found, NotFound, Ambiguous {
    }

    public record Found(ImportFile file) implements Resolution {
    }

    public record NotFound() implements Resolution {
    }

    public record Ambiguous(int matches) implements Resolution {
    }

    private record Source(Map<String, Path> looseByName, Map<String, Path> looseByLowerName, ZipFile archive,
                          Map<String, ZipEntry> entriesByPath, Map<String, ZipEntry> entriesByLowerPath,
                          Map<String, List<ZipEntry>> entriesByName, Map<String, List<ZipEntry>> entriesByLowerName) {
    }

    private final List<Source> sources;

    private ImportFiles(List<Source> sources) {
        this.sources = sources;
    }

    /** @param stagedDirs batch staging directories, most specific first; missing ones are skipped */
    public static ImportFiles open(List<Path> stagedDirs, int maxArchiveEntries) {
        List<Source> sources = new ArrayList<>();
        try {
            for (Path dir : stagedDirs) {
                if (dir != null && Files.isDirectory(dir)) {
                    sources.add(openSource(dir, maxArchiveEntries));
                }
            }
        } catch (RuntimeException e) {
            new ImportFiles(sources).close();
            throw e;
        }
        return new ImportFiles(sources);
    }

    public boolean isEmpty() {
        return sources.stream().allMatch(s -> s.looseByName().isEmpty() && s.entriesByPath().isEmpty());
    }

    public Resolution resolve(String reference) {
        String path = normalize(reference);
        String name = baseName(path);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (Source source : sources) {
            Resolution exact = resolveIn(source, path, name, source.looseByName(), source.entriesByPath(),
                    source.entriesByName());
            if (!(exact instanceof NotFound)) {
                return exact;
            }
            Resolution anyCase = resolveIn(source, lowerPath, lowerName, source.looseByLowerName(),
                    source.entriesByLowerPath(), source.entriesByLowerName());
            if (!(anyCase instanceof NotFound)) {
                return anyCase;
            }
        }
        return new NotFound();
    }

    private static Resolution resolveIn(Source source, String path, String name, Map<String, Path> loose,
                                        Map<String, ZipEntry> byPath, Map<String, List<ZipEntry>> byName) {
        if (path.equals(name) && loose.containsKey(name)) {
            return new Found(looseFile(loose.get(name)));
        }
        if (byPath.containsKey(path)) {
            return new Found(entryFile(source.archive(), byPath.get(path)));
        }
        List<ZipEntry> named = byName.getOrDefault(name, List.of());
        if (named.size() == 1) {
            return new Found(entryFile(source.archive(), named.get(0)));
        }
        if (named.size() > 1) {
            return new Ambiguous(named.size());
        }
        if (loose.containsKey(name)) {
            return new Found(looseFile(loose.get(name)));
        }
        return new NotFound();
    }

    /** Backslashes to slashes, no leading "./" or "/", trimmed. */
    public static String normalize(String reference) {
        String path = reference == null ? "" : reference.trim().replace('\\', '/');
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.startsWith("./") ? path.substring(2) : path.substring(1);
        }
        return path;
    }

    public static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static Source openSource(Path dir, int maxArchiveEntries) {
        Map<String, Path> looseByName = new HashMap<>();
        Map<String, Path> looseByLowerName = new HashMap<>();
        Path looseDir = dir.resolve(LOOSE_DIR);
        if (Files.isDirectory(looseDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(looseDir)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    looseByName.put(name, file);
                    looseByLowerName.putIfAbsent(name.toLowerCase(Locale.ROOT), file);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not list staged import files in " + looseDir, e);
            }
        }

        Map<String, ZipEntry> byPath = new HashMap<>();
        Map<String, ZipEntry> byLowerPath = new HashMap<>();
        Map<String, List<ZipEntry>> byName = new HashMap<>();
        Map<String, List<ZipEntry>> byLowerName = new HashMap<>();
        ZipFile archive = null;
        Path archivePath = dir.resolve(ARCHIVE);
        if (Files.isRegularFile(archivePath)) {
            archive = openZip(archivePath);
            int count = 0;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > maxArchiveEntries) {
                    closeQuietly(archive);
                    throw BulkImportException.badRequest("INVALID_ARCHIVE",
                            "The ZIP holds more than " + maxArchiveEntries + " entries.");
                }
                String path = normalize(entry.getName());
                String name = baseName(path);
                if (entry.isDirectory() || name.isEmpty() || path.startsWith("__MACOSX/") || name.startsWith("._")) {
                    continue;
                }
                byPath.put(path, entry);
                byLowerPath.putIfAbsent(path.toLowerCase(Locale.ROOT), entry);
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(entry);
                byLowerName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(entry);
            }
        }
        return new Source(looseByName, looseByLowerName, archive, byPath, byLowerPath, byName, byLowerName);
    }

    /** UTF-8 names first; archives from older Windows tools use the DOS code page. */
    private static ZipFile openZip(Path path) {
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("IBM437"))) {
            try {
                ZipFile zip = new ZipFile(path.toFile(), charset);
                zip.stream().count(); // forces every entry name to decode now
                return zip;
            } catch (IllegalArgumentException malformedName) {
                // try the next charset
            } catch (ZipException e) {
                break;
            } catch (IOException e) {
                throw new IllegalStateException("Could not read staged archive " + path, e);
            }
        }
        throw BulkImportException.badRequest("INVALID_ARCHIVE", "The uploaded file isn't a valid ZIP archive.");
    }

    private static ImportFile looseFile(Path file) {
        return new ImportFile() {
            public String name() {
                return file.getFileName().toString();
            }

            public long size() {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return -1;
                }
            }

            public InputStream open() throws IOException {
                return Files.newInputStream(file);
            }
        };
    }

    private static ImportFile entryFile(ZipFile archive, ZipEntry entry) {
        return new ImportFile() {
            public String name() {
                return baseName(normalize(entry.getName()));
            }

            public long size() {
                return entry.getSize();
            }

            public InputStream open() throws IOException {
                return archive.getInputStream(entry);
            }
        };
    }

    @Override
    public void close() {
        sources.forEach(s -> closeQuietly(s.archive()));
    }

    private static void closeQuietly(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
