package com.oak.dms.editing;

import com.oak.dms.scanning.helper.SizeLimitingInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where the body of a WOPI save waits while it is checked and handed to the ingestion gate (DOC-12).
 * <p>
 * The body used to be read into a {@code byte[]}: up to 200 MiB in heap, twice over while it was being read, and held for
 * as long as the gate took - eight concurrent 64 MiB saves exhausted a 900 MiB heap. It is now streamed to a private file
 * through fixed 8 KiB buffers, counted as it arrives, and read back from the file twice (the signature check, then the
 * gate). Heap use no longer depends on the size of the document.
 *
 * <h2>The limit</h2>
 * The caller's cap is enforced on the bytes that actually arrive ({@link SizeLimitingInputStream}): exactly the cap is
 * accepted, one byte more is refused with 413 and reading stops there. A declared length above the cap is refused before
 * anything is created. The declared length is never taken as the size - the counted bytes are.
 *
 * <h2>Where, and who can see it</h2>
 * One dedicated directory ({@code dms.editing.spool-dir}, default {@code <java.io.tmpdir>/oak-dms-wopi-spool}), created
 * private to the service user (0700 on POSIX), refused if it is not a plain directory this user owns. Files are
 * {@code wopi-save-<random uuid>.part}, created exclusively with owner-only permissions; no part of the name comes from
 * the request. The directory is not served by anything. Contents and tokens are never logged.
 *
 * <h2>Disk</h2>
 * The per-request cap does not bound the total, so two safeguards apply per instance: at most
 * {@code dms.editing.spool-max-active} spool files exist at once (a further save waits {@code dms.editing.spool-wait}, then
 * is refused with 503), and a save is refused with 507 unless the volume keeps {@code dms.editing.spool-min-free-bytes} free
 * after counting what the saves in progress may still write (their declared size, or the cap when it is not declared).
 *
 * <h2>Cleanup, including after a crash</h2>
 * {@link Spooled#close()} deletes the file in a {@code finally}; a JVM that dies cannot. So every live spool file holds an
 * exclusive operating-system lock on one byte far beyond its data, for as long as it exists. {@link #sweepAbandoned}
 * deletes a file only if it is old enough <b>and</b> that lock can be taken - which proves no upload, in this process or
 * another one sharing the directory, is using it (a dead process's locks are released by the OS). Symbolic links and
 * anything not named like a spool file are never touched. No database is involved.
 */
@Slf4j
@Component
public class WopiSaveSpool {

    static final String PREFIX = "wopi-save-";
    static final String SUFFIX = ".part";
    /**
     * The locked region: one byte far past any data, so reading the file's content through another handle is never
     * blocked (on Windows locks are mandatory) while two lock attempts still conflict.
     */
    static final long LOCK_POSITION = Long.MAX_VALUE - 1;

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> OWNER_FILE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIR = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final Path directory;
    private final Semaphore slots;
    private final long waitMillis;
    private final long minFreeBytes;
    private final Duration abandonedAfter;
    private final Object budgetLock = new Object();
    private long reservedBytes;

    public WopiSaveSpool(@Value("${dms.editing.spool-dir:}") String directory,
                         @Value("${dms.editing.spool-max-active:32}") int maxActive,
                         @Value("${dms.editing.spool-wait:PT15S}") Duration wait,
                         @Value("${dms.editing.spool-min-free-bytes:536870912}") long minFreeBytes,
                         @Value("${dms.editing.spool-abandoned-after:PT6H}") Duration abandonedAfter) {
        this.directory = directory == null || directory.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "oak-dms-wopi-spool") : Path.of(directory.trim());
        this.slots = new Semaphore(Math.max(1, maxActive), true);
        this.waitMillis = Math.max(0, wait.toMillis());
        this.minFreeBytes = Math.max(0, minFreeBytes);
        this.abandonedAfter = abandonedAfter;
    }

    /**
     * Streams a save's body to a private file.
     *
     * @param declaredBytes the request's Content-Length, or -1 (chunked / not declared); only used to refuse early and to
     *                      reserve disk - the size is what is counted
     * @param maxBytes      the largest save accepted
     * @throws ResponseStatusException 413 over the cap, 507 not enough disk, 503 too many saves in progress
     * @throws IOException             the body could not be read or the file could not be written (nothing is left behind)
     */
    public Spooled receive(InputStream body, long declaredBytes, long maxBytes) throws IOException {
        if (declaredBytes > maxBytes) {
            throw tooLarge();                                  // before anything exists
        }
        boolean slot = false;
        long reserved = 0;
        Path file = null;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            slot = acquireSlot();
            reserved = reserveDisk(declaredBytes >= 0 ? declaredBytes : maxBytes);
            Path dir = ensureDirectory();
            file = dir.resolve(PREFIX + UUID.randomUUID() + SUFFIX);
            channel = open(file);
            lock = channel.lock(LOCK_POSITION, 1, false);      // held until close: "this file is in use"

            SizeLimitingInputStream limited = new SizeLimitingInputStream(body, maxBytes);
            try (OutputStream out = new NonClosing(Channels.newOutputStream(channel))) {
                limited.transferTo(out);
            } catch (IOException e) {
                if (limited.limitExceeded()) {
                    throw tooLarge();                          // reading stopped at the first byte over the cap
                }
                throw e;
            }
            if (channel.size() != limited.count()) {
                throw new IOException("The spooled save is " + channel.size() + " bytes but " + limited.count()
                        + " were read");
            }
            Spooled spooled = new Spooled(file, channel, lock, limited.count(), reserved, slot);
            file = null;                                       // now owned by the caller
            return spooled;
        } catch (Throwable failure) {
            discard(file, channel, lock);
            release(reserved, slot);
            throw failure;
        }
    }

    /** One spooled save. {@link #close()} removes the file and gives back its disk reservation and slot. */
    public final class Spooled implements AutoCloseable {
        private final Path file;
        private final FileChannel channel;
        private final FileLock lock;
        private final long sizeBytes;
        private final long reserved;
        private final boolean slot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Spooled(Path file, FileChannel channel, FileLock lock, long sizeBytes, long reserved, boolean slot) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
            this.sizeBytes = sizeBytes;
            this.reserved = reserved;
            this.slot = slot;
        }

        /** The bytes that really arrived - the length to hand on. */
        public long sizeBytes() {
            return sizeBytes;
        }

        /** The content from the start, as many times as needed; the caller closes each stream before {@link #close()}. */
        public InputStream openStream() throws IOException {
            return Files.newInputStream(file, StandardOpenOption.READ);
        }

        Path path() {
            return file;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                discard(file, channel, lock);
            } finally {
                release(reserved, slot);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ crash recovery

    /** Deletes abandoned spool files older than the configured age. Safe to run any time, by any instance. */
    @Scheduled(fixedDelayString = "${dms.editing.spool-sweep-interval:PT15M}",
            initialDelayString = "${dms.editing.spool-sweep-initial-delay:PT2M}")
    public void sweep() {
        try {
            int removed = sweepAbandoned(abandonedAfter);
            if (removed > 0) {
                log.info("Removed {} abandoned WOPI save spool file(s) from {}", removed, directory);
            }
        } catch (RuntimeException e) {
            log.warn("The WOPI spool sweep failed: {}", e.toString());
        }
    }

    /**
     * A spool file is removed only if it is a regular file (never a link) named like one of ours, older than
     * {@code olderThan}, and its in-use lock can be taken - i.e. nothing holds it. A file that is in use here (the lock
     * overlaps one this process holds) or elsewhere (the lock is refused) is left alone.
     *
     * @return how many files were removed
     */
    public int sweepAbandoned(Duration olderThan) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        FileTime cutoff = FileTime.from(Instant.now().minus(olderThan));
        int removed = 0;
        try (DirectoryStream<Path> candidates = Files.newDirectoryStream(directory, PREFIX + "*" + SUFFIX)) {
            for (Path candidate : candidates) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || attributes.lastModifiedTime().compareTo(cutoff) > 0) {
                        continue;
                    }
                    if (removeIfUnused(candidate)) {
                        removed++;
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Could not examine WOPI spool file {}: {}", candidate.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Could not list the WOPI spool directory {}: {}", directory, e.toString());
        }
        return removed;
    }

    private boolean removeIfUnused(Path candidate) throws IOException {
        FileChannel channel = FileChannel.open(candidate, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            lock = channel.tryLock(LOCK_POSITION, 1, false);
            if (lock == null) {
                return false;                                  // another process is using it
            }
        } catch (OverlappingFileLockException inUseHere) {
            channel.close();
            return false;                                      // an upload in this process is using it
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        lock.release();
        channel.close();                                       // Windows cannot delete an open file
        return Files.deleteIfExists(candidate);
    }

    // ------------------------------------------------------------------------------------------ guts

    private boolean acquireSlot() {
        try {
            if (!slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Too many saves are in progress. Try again in a moment.");
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The save was interrupted.");
        }
    }

    /** Counts what the saves in progress may still write against the volume's free space. */
    private long reserveDisk(long bytes) throws IOException {
        Path dir = ensureDirectory();
        synchronized (budgetLock) {
            FileStore store = Files.getFileStore(dir);
            long free = store.getUsableSpace();
            if (free - reservedBytes - bytes < minFreeBytes) {
                throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                        "There is not enough temporary disk space to save this file right now.");
            }
            reservedBytes += bytes;
            return bytes;
        }
    }

    private void release(long reserved, boolean slot) {
        if (reserved > 0) {
            synchronized (budgetLock) {
                reservedBytes = Math.max(0, reservedBytes - reserved);
            }
        }
        if (slot) {
            slots.release();
        }
    }

    private static FileChannel open(Path file) throws IOException {
        return POSIX
                ? FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE), posix(OWNER_FILE))
                : FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /** Closes whatever is open and deletes the file; a delete that fails is logged (path only) and left for the sweep. */
    private static void discard(Path file, FileChannel channel, FileLock lock) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            log.debug("Could not release the lock on a WOPI spool file: {}", e.toString());
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            log.debug("Could not close a WOPI spool file: {}", e.toString());
        }
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not delete WOPI spool file {} - the sweep will retry: {}", file.getFileName(), e.toString());
            }
        }
    }

    private Path ensureDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = directory.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                if (POSIX) {
                    Files.createDirectory(directory, posix(OWNER_DIR));
                } else {
                    Files.createDirectory(directory);
                }
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Either another request created it a moment ago (fine) or something else is squatting on the name:
                // the check below tells them apart.
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("The WOPI spool path " + directory + " exists and is not a plain directory");
            }
        }
        requirePrivate(directory);
        return directory;
    }

    /** POSIX: owned by this user and closed to everyone else - repaired if it is ours and too open, refused otherwise. */
    private static void requirePrivate(Path dir) throws IOException {
        if (!POSIX) {
            return;                                            // a Windows user's temp area is already private
        }
        Object owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
        Object me = dir.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if (!owner.equals(me)) {
            throw new IOException("The WOPI spool directory " + dir + " is not owned by the service user");
        }
        if (!OWNER_DIR.containsAll(Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS))) {
            Files.setPosixFilePermissions(dir, OWNER_DIR);
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> posix(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "That file is too large to save.");
    }

    /** Lets the channel-backed stream be used in try-with-resources without closing the channel that holds the in-use lock. */
    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** The directory spool files live in (for operators' diagnostics and tests). */
    public Path directory() {
        return directory;
    }
}
