package com.oak.dms.common.io;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * Temporary files for spooling document content, created somewhere only this
 * process can read.
 * <p>
 * Every scratch copy of a document passes through here - uploads being
 * encrypted, archives being virus-scanned, exports being zipped. Straight
 * {@code Files.createTempFile(prefix, suffix)} puts those in
 * {@code java.io.tmpdir}, which on Linux is {@code /tmp}: world-writable and
 * world-readable. The file itself lands at 0600, but the directory is shared,
 * so anything else on the host can list what is being processed and when, and
 * a hostile local process can race the path it is about to occupy.
 * <p>
 * So everything goes in one directory this process owns, at 0700. The file
 * permissions are stated explicitly rather than relying on the JDK's default,
 * because that default is an implementation detail of {@code TempFileHelper}
 * and not something the contract promises.
 * <p>
 * On a file system with no POSIX permissions (a developer's Windows machine)
 * the attributes are simply omitted: there the per-user temp directory is
 * already private, and passing POSIX attributes would throw.
 */
public final class TempFiles {

    /** Owner read/write only - nothing for group or other. */
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Owner needs execute on the directory as well, or it cannot be entered. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * Resolved once. A failure here is not recoverable by retrying - the temp
     * directory either works or the process has nowhere to spool - so it is
     * better to find out at class-init than on the first upload.
     */
    private static final Path DIR = privateDirectory();

    private TempFiles() {
    }

    /**
     * A new empty file that only this user can open, deleted by the caller.
     *
     * @param prefix names the subsystem that made it, so a leftover file in a
     *               running system can be traced back to what left it
     */
    public static Path create(String prefix, String suffix) throws IOException {
        return POSIX
                ? Files.createTempFile(DIR, prefix, suffix, asAttribute(OWNER_ONLY_FILE))
                : Files.createTempFile(DIR, prefix, suffix);
    }

    private static FileAttribute<Set<PosixFilePermission>> asAttribute(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    private static Path privateDirectory() {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        Path preferred = base.resolve("oak-dms");
        try {
            if (!POSIX) {
                Files.createDirectories(preferred);
                return preferred;
            }
            try {
                return Files.createDirectory(preferred, asAttribute(OWNER_ONLY_DIR));
            } catch (java.nio.file.FileAlreadyExistsException alreadyThere) {
                // Left behind by a previous run of this service - reuse it,
                // but only after checking it is still ours and still private.
                // If it is not, something else is squatting on the name and
                // this process must not write documents into it.
                if (isOursAndPrivate(preferred)) {
                    return preferred;
                }
                // A fresh randomly-named directory instead: unique, so it
                // cannot be pre-created by anyone, and private from birth.
                return Files.createTempDirectory(base, "oak-dms-", asAttribute(OWNER_ONLY_DIR));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not create a private temporary directory under " + base, e);
        }
    }

    private static boolean isOursAndPrivate(Path dir) throws IOException {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.getOwner(dir).equals(
                dir.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name")))) {
            return false;
        }
        return OWNER_ONLY_DIR.containsAll(Files.getPosixFilePermissions(dir));
    }
}
