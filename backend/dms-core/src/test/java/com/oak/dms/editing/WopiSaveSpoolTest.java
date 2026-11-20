package com.oak.dms.editing;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOC-12: the spool that replaced the whole-body byte[] of a WOPI save. Real files in a temporary directory; the bodies
 * are generated on the fly, so no test holds a document in memory.
 */
class WopiSaveSpoolTest {

    private static final long MIB = 1024 * 1024;

    @TempDir
    Path tmp;

    private Path spoolDir() {
        return tmp.resolve("spool");
    }

    private WopiSaveSpool spool(int maxActive, Duration wait, long minFree) {
        return new WopiSaveSpool(spoolDir().toString(), maxActive, wait, minFree, Duration.ofHours(6));
    }

    private WopiSaveSpool spool() {
        return spool(8, Duration.ofSeconds(5), 0);
    }

    /** A body of {@code size} bytes of a repeating pattern that counts what has been read from it. */
    private static final class Body extends InputStream {
        final AtomicLong read = new AtomicLong();
        private final long size;
        private long position;
        private final long failAfter;

        Body(long size) {
            this(size, -1);
        }

        Body(long size, long failAfter) {
            this.size = size;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (failAfter >= 0 && position >= failAfter) {
                throw new IOException("connection reset");
            }
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - position);
            if (failAfter >= 0) {
                n = (int) Math.min(n, failAfter - position);
            }
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) ((position + i) % 251);
            }
            position += n;
            read.addAndGet(n);
            return n;
        }
    }

    private static byte[] readAll(WopiSaveSpool.Spooled save) throws IOException {
        try (InputStream in = save.openStream()) {
            return in.readAllBytes();
        }
    }

    private long filesInSpool() throws IOException {
        if (!Files.exists(spoolDir())) {
            return 0;
        }
        try (Stream<Path> list = Files.list(spoolDir())) {
            return list.count();
        }
    }

    // ------------------------------------------------------------------------------------------ the ordinary save

    @Test
    void aSmallSave_isStoredExactly_countedFromWhatArrived_andRemovedOnClose() throws Exception {
        WopiSaveSpool spool = spool();
        byte[] expected = new byte[5000];
        new Body(5000).read(expected, 0, 5000);

        try (WopiSaveSpool.Spooled save = spool.receive(new Body(5000), 5000, 200 * MIB)) {
            assertThat(save.sizeBytes()).isEqualTo(5000);
            assertThat(readAll(save)).isEqualTo(expected);
            assertThat(readAll(save)).as("it can be read again, for the second pass").isEqualTo(expected);
            assertThat(filesInSpool()).isEqualTo(1);
        }

        assertThat(filesInSpool()).isZero();
    }

    @Test
    void anUnknownLength_orAChunkedBody_isCountedToo() throws Exception {
        try (WopiSaveSpool.Spooled save = spool().receive(new Body(70_000), -1, 200 * MIB)) {
            assertThat(save.sizeBytes()).isEqualTo(70_000);
        }
    }

    @Test
    void theDeclaredLengthIsNeverTakenAsTheSize() throws Exception {
        try (WopiSaveSpool.Spooled save = spool().receive(new Body(9000), 10, 200 * MIB)) {
            assertThat(save.sizeBytes()).as("what arrived, not what was declared").isEqualTo(9000);
        }
    }

    @Test
    void anEmptyBody_isAnEmptyFile() throws Exception {
        try (WopiSaveSpool.Spooled save = spool().receive(new Body(0), 0, 100)) {
            assertThat(save.sizeBytes()).isZero();
            assertThat(readAll(save)).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------ the limit

    @Test
    void exactlyTheCap_isAccepted_andOneByteMore_isRefused413_afterReadingNoMoreThanTheBuffer() throws Exception {
        WopiSaveSpool spool = spool();
        try (WopiSaveSpool.Spooled save = spool.receive(new Body(1000), -1, 1000)) {
            assertThat(save.sizeBytes()).isEqualTo(1000);
        }
        Body over = new Body(100_000);
        assertThatThrownBy(() -> spool.receive(over, -1, 1000)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(413));
        assertThat(over.read.get()).as("reading stopped at the first buffer past the cap").isLessThanOrEqualTo(1000 + 16 * 1024);
        assertThat(filesInSpool()).as("nothing left behind").isZero();
    }

    @Test
    void theRealCap_200MiB_isAccepted_and200MiBPlusOneByteIsRefused() throws Exception {
        WopiSaveSpool spool = spool();
        long cap = 200 * MIB;
        try (WopiSaveSpool.Spooled save = spool.receive(new Body(cap), -1, cap)) {
            assertThat(save.sizeBytes()).isEqualTo(cap);
        }
        assertThatThrownBy(() -> spool.receive(new Body(cap + 1), -1, cap)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(413));
        assertThat(filesInSpool()).isZero();
    }

    @Test
    void aDeclaredLengthOverTheCap_isRefusedBeforeAnythingIsCreatedOrRead() {
        Body body = new Body(10_000);

        assertThatThrownBy(() -> spool().receive(body, 1001, 1000)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(413));

        assertThat(body.read.get()).isZero();
        assertThat(Files.exists(spoolDir())).as("not even the directory").isFalse();
    }

    // ------------------------------------------------------------------------------------------ failures

    @Test
    void aBodyThatFailsPartWay_leavesNothing_andGivesTheSlotBack() throws Exception {
        WopiSaveSpool spool = spool(1, Duration.ofMillis(200), 0);

        assertThatThrownBy(() -> spool.receive(new Body(100_000, 30_000), -1, 200 * MIB))
                .isInstanceOf(IOException.class).hasMessageContaining("connection reset");

        assertThat(filesInSpool()).isZero();
        try (WopiSaveSpool.Spooled save = spool.receive(new Body(10), 10, 100)) {     // the only slot is free again
            assertThat(save.sizeBytes()).isEqualTo(10);
        }
    }

    @Test
    void aSpoolDirectoryThatCannotBeCreated_failsTheSave_withoutLeakingASlot() throws Exception {
        Path blocker = Files.writeString(tmp.resolve("not-a-directory"), "x");
        WopiSaveSpool spool = new WopiSaveSpool(blocker.resolve("spool").toString(), 1, Duration.ofMillis(200), 0,
                Duration.ofHours(1));

        for (int attempt = 0; attempt < 3; attempt++) {                                // never 503: the slot comes back
            assertThatThrownBy(() -> spool.receive(new Body(10), 10, 100)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aSpoolPathThatIsAFile_isRefused() throws Exception {
        Files.writeString(spoolDir(), "squatter");

        assertThatThrownBy(() -> spool().receive(new Body(10), 10, 100)).isInstanceOf(IOException.class)
                .hasMessageContaining("not a plain directory");
    }

    // ------------------------------------------------------------------------------------------ disk and slots

    @Test
    void notEnoughFreeDisk_isRefused507_andTheReservationOfASaveInProgressCounts() throws Exception {
        Files.createDirectories(spoolDir());
        long usable = Files.getFileStore(spoolDir()).getUsableSpace();
        Assumptions.assumeTrue(usable > 400 * MIB, "needs some free disk");
        WopiSaveSpool spool = spool(8, Duration.ofSeconds(1), usable - 150 * MIB);

        assertThatThrownBy(() -> spool.receive(new Body(10), 300 * MIB, 400 * MIB))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(507));

        try (WopiSaveSpool.Spooled first = spool.receive(new Body(10), 100 * MIB, 400 * MIB)) {   // may still write 100 MiB
            assertThatThrownBy(() -> spool.receive(new Body(10), 100 * MIB, 400 * MIB))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(507));
            assertThat(first.sizeBytes()).isEqualTo(10);
        }
        try (WopiSaveSpool.Spooled again = spool.receive(new Body(10), 100 * MIB, 400 * MIB)) {   // reservation given back
            assertThat(again.sizeBytes()).isEqualTo(10);
        }
        assertThat(filesInSpool()).isZero();
    }

    @Test
    void aChunkedSave_reservesTheWholeCap() throws Exception {
        Files.createDirectories(spoolDir());
        long usable = Files.getFileStore(spoolDir()).getUsableSpace();
        Assumptions.assumeTrue(usable > 400 * MIB, "needs some free disk");
        WopiSaveSpool spool = spool(8, Duration.ofSeconds(1), usable - 50 * MIB);

        assertThatThrownBy(() -> spool.receive(new Body(10), -1, 200 * MIB))      // may write up to the cap: more than allowed
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(507));
    }

    @Test
    void tooManySavesInProgress_waitThenAreRefused503_andCloseGivesTheSlotBack_once() throws Exception {
        WopiSaveSpool spool = spool(1, Duration.ofMillis(150), 0);
        WopiSaveSpool.Spooled first = spool.receive(new Body(10), 10, 100);

        assertThatThrownBy(() -> spool.receive(new Body(10), 10, 100)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(503));

        first.close();
        first.close();                                                                  // idempotent: must not add a second slot
        WopiSaveSpool.Spooled second = spool.receive(new Body(10), 10, 100);
        assertThatThrownBy(() -> spool.receive(new Body(10), 10, 100)).isInstanceOf(ResponseStatusException.class);
        second.close();
        assertThat(filesInSpool()).isZero();
    }

    @Test
    void closingASaveWhoseFileIsAlreadyGone_isNotAnError_andStillGivesBackTheSlot() throws Exception {
        WopiSaveSpool spool = spool(1, Duration.ofMillis(150), 0);
        WopiSaveSpool.Spooled save = spool.receive(new Body(100), 100, 1000);
        Files.deleteIfExists(save.path());                                              // the sweep, or an operator, got there first

        save.close();

        try (WopiSaveSpool.Spooled next = spool.receive(new Body(10), 10, 100)) {
            assertThat(next.sizeBytes()).isEqualTo(10);
        }
    }

    @Test
    void aFileThatCannotBeDeleted_isNotAnErrorOnClose_andTheSweepRemovesItLater() throws Exception {
        Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "needs POSIX permissions to make a delete fail");
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root ignores directory permissions");
        WopiSaveSpool spool = spool();
        WopiSaveSpool.Spooled save = spool.receive(new Body(100), 100, 1000);
        Files.setPosixFilePermissions(spoolDir(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            save.close();                                                               // the delete is refused: logged, not thrown
            assertThat(filesInSpool()).isEqualTo(1);
        } finally {
            Files.setPosixFilePermissions(spoolDir(), Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
        Files.setLastModifiedTime(save.path(), FileTime.from(Instant.now().minus(Duration.ofHours(10))));
        assertThat(spool.sweepAbandoned(Duration.ofHours(6))).isEqualTo(1);
        assertThat(filesInSpool()).isZero();
    }

    // ------------------------------------------------------------------------------------------ crash recovery

    private Path abandoned(String name, Duration age) throws IOException {
        Files.createDirectories(spoolDir());
        Path file = Files.write(spoolDir().resolve(name), new byte[]{1, 2, 3});
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return file;
    }

    @Test
    void theSweep_removesAbandonedFiles_keepsYoungOnes_andNeverTouchesOtherFiles() throws Exception {
        WopiSaveSpool spool = spool();
        Path old = abandoned("wopi-save-11111111-1111-1111-1111-111111111111.part", Duration.ofHours(10));
        Path young = abandoned("wopi-save-22222222-2222-2222-2222-222222222222.part", Duration.ofMinutes(5));
        Path unrelated = abandoned("report.docx", Duration.ofDays(30));
        Path lookalikeDir = spoolDir().resolve("wopi-save-33333333-3333-3333-3333-333333333333.part");
        Files.createDirectory(lookalikeDir);
        Files.setLastModifiedTime(lookalikeDir, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        assertThat(spool.sweepAbandoned(Duration.ofHours(6))).isEqualTo(1);

        assertThat(old).doesNotExist();
        assertThat(young).exists();
        assertThat(unrelated).exists();
        assertThat(lookalikeDir).isDirectory();
        assertThat(spool.sweepAbandoned(Duration.ofHours(6))).as("running it again is safe").isZero();
    }

    @Test
    void theSweep_neverRemovesAFileAnUploadIsStillUsing_howeverOldItLooks() throws Exception {
        WopiSaveSpool spool = spool();
        try (WopiSaveSpool.Spooled active = spool.receive(new Body(1000), 1000, 5000)) {
            Files.setLastModifiedTime(active.path(), FileTime.from(Instant.now().minus(Duration.ofDays(3))));
            Path abandoned = abandoned("wopi-save-44444444-4444-4444-4444-444444444444.part", Duration.ofDays(3));

            assertThat(spool.sweepAbandoned(Duration.ofHours(1))).as("only the abandoned one").isEqualTo(1);

            assertThat(abandoned).doesNotExist();
            assertThat(active.path()).exists();
            assertThat(readAll(active)).hasSize(1000);
        }
        assertThat(filesInSpool()).isZero();
    }

    @Test
    void theSweep_doesNotFollowALinkOutOfTheSpoolDirectory() throws Exception {
        Path outside = Files.writeString(tmp.resolve("precious.txt"), "do not delete");
        Files.setLastModifiedTime(outside, FileTime.from(Instant.now().minus(Duration.ofDays(9))));
        Files.createDirectories(spoolDir());
        Path link = spoolDir().resolve("wopi-save-55555555-5555-5555-5555-555555555555.part");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links are not available here: " + e);
        }

        spool().sweepAbandoned(Duration.ofHours(1));

        assertThat(outside).exists().hasContent("do not delete");
        assertThat(Files.isSymbolicLink(link)).as("the link itself is left alone too").isTrue();
    }

    /** A separate process that takes a spool file's in-use lock and holds it until it is killed. */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(Path.of(args[0]),
                    java.nio.file.StandardOpenOption.WRITE);
            channel.lock(WopiSaveSpool.LOCK_POSITION, 1, false);
            System.out.println("locked");
            System.out.flush();
            Thread.sleep(600_000);
        }
    }

    @Test
    void theSweep_leavesAFileThatAnotherProcessIsUsing_andRemovesItOnceThatProcessIsGone() throws Exception {
        Path inUse = abandoned("wopi-save-66666666-6666-6666-6666-666666666666.part", Duration.ofDays(2));
        Path classes = Path.of(LockHolder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java");
        Process other = new ProcessBuilder(javaBin.toString(), "-cp", classes.toString(),
                "com.oak.dms.editing.WopiSaveSpoolTest$LockHolder", inUse.toString()).redirectErrorStream(true).start();
        try {
            java.io.BufferedReader out = new java.io.BufferedReader(new java.io.InputStreamReader(other.getInputStream()));
            String line = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && !"locked".equals(line)) {
                line = out.readLine();
            }
            assertThat(line).as("the other process took the lock").isEqualTo("locked");

            assertThat(spool().sweepAbandoned(Duration.ofHours(1))).as("in use by another process: left alone").isZero();
            assertThat(inUse).exists();
        } finally {
            other.destroyForcibly();                                                    // a crash: the OS releases its lock
            other.waitFor(30, TimeUnit.SECONDS);
        }

        // The OS releases a dead process's locks a moment after it exits; a sweep that is a little early simply tries again
        // at the next interval, so the test polls for it.
        int removed = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (removed == 0 && System.nanoTime() < deadline) {
            removed = spool().sweepAbandoned(Duration.ofHours(1));
            if (removed == 0) {
                Thread.sleep(50);
            }
        }
        assertThat(removed).as("abandoned once that process is gone").isEqualTo(1);
        assertThat(inUse).doesNotExist();
    }

    @Test
    void theSweep_ofADirectoryThatDoesNotExist_isANoOp() {
        assertThat(spool().sweepAbandoned(Duration.ZERO)).isZero();
    }

    // ------------------------------------------------------------------------------------------ concurrency and permissions

    @Test
    void manySavesAtOnce_eachGetItsOwnBytes_andNoOneRemovesAnothersFile() throws Exception {
        WopiSaveSpool spool = spool(6, Duration.ofSeconds(20), 0);
        int saves = 24;
        ExecutorService pool = Executors.newFixedThreadPool(saves);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < saves; i++) {
            long size = 100_000 + i * 977L;
            results.add(pool.submit(() -> {
                try (WopiSaveSpool.Spooled save = spool.receive(new Body(size), size, 200 * MIB)) {
                    Thread.sleep(20);                                                  // others come and go meanwhile
                    byte[] expected = new byte[(int) size];
                    new Body(size).read(expected, 0, expected.length);
                    return save.sizeBytes() == size && java.util.Arrays.equals(expected, readAll(save));
                }
            }));
        }
        for (Future<Boolean> result : results) {
            assertThat(result.get(60, TimeUnit.SECONDS)).isTrue();
        }
        pool.shutdown();

        assertThat(filesInSpool()).isZero();
    }

    @Test
    void onPosix_theDirectoryAndFilesAreOwnerOnly_andNamedWithoutAnythingFromTheRequest() throws Exception {
        Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        try (WopiSaveSpool.Spooled save = spool().receive(new Body(10), 10, 100)) {
            assertThat(Files.getPosixFilePermissions(spoolDir())).isEqualTo(Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            assertThat(Files.getPosixFilePermissions(save.path())).isEqualTo(Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void theFileName_isAFreshRandomName_inTheSpoolDirectory() throws Exception {
        try (WopiSaveSpool.Spooled first = spool().receive(new Body(10), 10, 100);
             WopiSaveSpool.Spooled second = spool().receive(new Body(10), 10, 100)) {
            assertThat(first.path().getFileName().toString())
                    .matches("wopi-save-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part");
            assertThat(first.path().getFileName()).isNotEqualTo(second.path().getFileName());
            assertThat(first.path().getParent()).isEqualTo(spoolDir());
        }
    }
}
