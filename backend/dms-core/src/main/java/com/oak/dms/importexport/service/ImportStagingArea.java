package com.oak.dms.importexport.service;

import com.oak.dms.importexport.config.BulkImportProperties;
import com.oak.dms.importexport.exception.BulkImportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Local-disk staging for bulk imports, under {@code dms.import.staging-dir}:
 * <pre>
 *   incoming/{random}/   files being received for a batch not yet saved
 *   batches/{batchId}/   archive.zip and/or files/ - see {@link ImportFiles}
 *   sessions/{id}.part   resumable upload sessions (UploadSessionService)
 * </pre>
 * Every path is built from UUIDs; an uploaded file's own name is used only
 * as a file name inside files/, and only after it has been reduced to a
 * plain base name.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportStagingArea {

    private static final String SOURCE_POINTER = "resubmit-of";

    private final BulkImportProperties properties;

    public Path root() {
        return Path.of(properties.getStagingDir()).toAbsolutePath().normalize();
    }

    public Path batchDir(UUID batchId) {
        return root().resolve("batches").resolve(batchId.toString());
    }

    public Path sessionFile(UUID sessionId) {
        return root().resolve("sessions").resolve(sessionId + ".part");
    }

    public Path newIncomingDir() {
        Path dir = root().resolve("incoming").resolve(UUID.randomUUID().toString());
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create import staging directory " + dir, e);
        }
    }

    /** Saves loose files under files/, by base name; two files of the same name are refused. */
    public void stageLooseFiles(Path incoming, List<MultipartFile> files) {
        Path looseDir = incoming.resolve(ImportFiles.LOOSE_DIR);
        Set<String> seen = new HashSet<>();
        try {
            Files.createDirectories(looseDir);
            for (MultipartFile file : files) {
                String name = safeName(file.getOriginalFilename());
                if (!seen.add(name)) {
                    throw BulkImportException.badRequest("DUPLICATE_FILE_NAME",
                            "Two uploaded files are both called \"" + name + "\". File names must be unique.");
                }
                Path target = looseDir.resolve(name).normalize();
                if (!target.getParent().equals(looseDir)) {
                    throw BulkImportException.badRequest("INVALID_FILE_NAME", "Invalid file name: " + name);
                }
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage import files", e);
        }
    }

    public void stageArchive(Path incoming, MultipartFile archive) {
        try (InputStream in = archive.getInputStream()) {
            Files.copy(in, incoming.resolve(ImportFiles.ARCHIVE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage import archive", e);
        }
    }

    /** Moves a completed upload session's file in as the batch's archive. */
    public void adoptSessionFile(Path incoming, Path sessionFile) {
        try {
            Files.move(sessionFile, incoming.resolve(ImportFiles.ARCHIVE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not move upload session file " + sessionFile, e);
        }
    }

    public Path moveToBatch(Path incoming, UUID batchId) {
        Path target = batchDir(batchId);
        try {
            Files.createDirectories(target.getParent());
            return Files.move(incoming, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not move staged import files to " + target, e);
        }
    }

    /** A resubmitted batch falls back to the original batch's files - see {@link #fileDirs(UUID)}. */
    public void linkToOriginal(Path incoming, UUID originalBatchId) {
        try {
            Files.writeString(incoming.resolve(SOURCE_POINTER), originalBatchId.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage resubmission link", e);
        }
    }

    /** Where a batch's files are looked up: its own directory, then (resubmissions) the original batch's. */
    public List<Path> fileDirs(UUID batchId) {
        return fileDirs(batchDir(batchId));
    }

    public List<Path> fileDirs(Path dir) {
        // A resubmission of a resubmission follows the chain back (bounded).
        List<Path> dirs = new java.util.ArrayList<>(List.of(dir));
        Path current = dir;
        for (int depth = 0; depth < 10; depth++) {
            Path pointer = current.resolve(SOURCE_POINTER);
            if (!Files.isRegularFile(pointer)) {
                break;
            }
            try {
                current = batchDir(UUID.fromString(Files.readString(pointer).trim()));
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Ignoring unreadable resubmission link {}: {}", pointer, e.getMessage());
                break;
            }
            if (dirs.contains(current)) {
                break;
            }
            dirs.add(current);
        }
        return dirs;
    }

    public void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete staged import file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not delete staged import directory {}: {}", dir, e.getMessage());
        }
    }

    /** incoming/ directories left behind by a request that died half way. */
    public void deleteAbandonedIncoming(Duration olderThan) {
        Path incoming = root().resolve("incoming");
        if (!Files.isDirectory(incoming)) {
            return;
        }
        FileTime cutoff = FileTime.from(Instant.now().minus(olderThan));
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(incoming)) {
            for (Path dir : dirs) {
                if (Files.getLastModifiedTime(dir).compareTo(cutoff) < 0) {
                    deleteQuietly(dir);
                }
            }
        } catch (IOException e) {
            log.warn("Could not sweep {}: {}", incoming, e.getMessage());
        }
    }

    /** The base name of an uploaded file, with anything a file system could misread removed. */
    static String safeName(String original) {
        String name = original == null ? "" : ImportFiles.baseName(ImportFiles.normalize(original));
        name = name.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw BulkImportException.badRequest("INVALID_FILE_NAME", "Every uploaded file needs a name.");
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
