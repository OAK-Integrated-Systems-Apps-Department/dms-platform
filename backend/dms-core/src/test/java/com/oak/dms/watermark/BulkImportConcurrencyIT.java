package com.oak.dms.watermark;

import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.ImportFileBlockedException;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportService;
import com.oak.dms.importexport.storage.IntakeVirusScanner;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * DOC-11 on real infrastructure: PostgreSQL, the application's real Hikari pool, real file storage behind the encryption
 * wrapper (whose key records use {@code REQUIRES_NEW}), real ClamAV. Storage and scan stalls are forced with latches
 * inside the stream and the scanner; "no lock", "no transaction" and "no connection" are observed (a raw JDBC connection
 * outside the pool, {@code pg_stat_activity}, {@code FOR UPDATE NOWAIT}, the Hikari MXBean, the Spring transaction state
 * of the thread doing the I/O) - never inferred from the absence of an annotation, and nothing relies on sleeping.
 * Before the fix the first test below starved the whole application.
 */
@SpringBootTest
class BulkImportConcurrencyIT extends WatermarkFixtures {

    private static final int TOTAL = 20_000;
    private static final int MIB = 1024 * 1024;

    @Autowired private BulkImportService bulk;
    @Autowired private com.oak.dms.importexport.config.ImportExportProperties props;
    @Autowired private ImportBatchRepository batches;
    @Autowired private ImportItemRepository items;
    @Autowired private DataSource dataSource;
    @Autowired private Environment env;
    @MockitoSpyBean private IntakeVirusScanner scanner;

    private DataSize capBefore;

    @AfterEach
    void restoreTheCap() {
        if (capBefore != null) {
            props.setMaxBatchSize(capBefore);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void cap(long bytes) {
        capBefore = props.getMaxBatchSize();
        props.setMaxBatchSize(DataSize.ofBytes(bytes));
    }

    private static byte[] file(int size) {
        byte[] bytes = new byte[size];
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    private UUID batchWith(Fixture f, String... filenames) {
        UUID typeId = documentRepository.findById(f.documentId()).orElseThrow().getDocumentTypeId();
        jdbc.update("insert into document_type_schemas (tenant_id, document_type_id, schema_version) values (?, ?, 1) "
                + "on conflict do nothing", f.tenantId(), typeId);
        ImportBatch batch = batches.save(ImportBatch.builder().tenantId(f.tenantId())
                .sourceType(ImportBatch.SourceType.bulk_import).documentTypeId(typeId).schemaVersion(1)
                .status(ImportBatch.ImportBatchStatus.uploading).totalRecords(filenames.length)
                .correlationId(UUID.randomUUID()).duplicateAction("keep_both").createdBy(f.userId()).build());
        int row = 2;
        for (String name : filenames) {
            items.save(ImportItem.builder().tenantId(f.tenantId()).importBatchId(batch.getImportBatchId())
                    .rowNumber(row++).filenameReference(name).rowValues("{\"title\":\"" + name + "\",\"fields\":{}}")
                    .status(ImportItem.ImportItemStatus.pending).build());
        }
        return batch.getImportBatchId();
    }

    private ImportItem item(UUID batch, String filename) {
        return items.findAllByBatchAndFilename(batch, filename).get(0);
    }

    private <T> Supplier<T> asTenant(Fixture f, java.util.concurrent.Callable<T> work) {
        return () -> {
            TenantContext.set(f.tenantId(), f.userId());
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                TenantContext.clear();
            }
        };
    }

    private <T> CompletableFuture<T> as(Fixture f, java.util.concurrent.Callable<T> work) {
        return CompletableFuture.supplyAsync(asTenant(f, work));
    }

    /** For work that blocks on purpose: the common pool has about one thread per CPU, so it cannot be relied on to hold them all. */
    private <T> CompletableFuture<T> as(Fixture f, java.util.concurrent.Callable<T> work, Executor executor) {
        return CompletableFuture.supplyAsync(asTenant(f, work), executor);
    }

    private Object upload(UUID batch, String name, InputStream stream, long length) {
        return bulk.receiveFile(batch, name, "application/pdf", length, stream);
    }

    private Connection diagnostics() throws SQLException {   // outside the application's pool
        return DriverManager.getConnection(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"));
    }

    private long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** True if nobody holds a lock on the batch row: a NOWAIT lock attempt from outside the pool succeeds at once. */
    private boolean batchRowIsFree(Connection c, UUID batch) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("select 1 from import_batches where import_batch_id = '" + batch + "' for update nowait");
            return true;
        } catch (SQLException e) {
            if ("55P03".equals(e.getSQLState())) {     // lock_not_available
                return false;
            }
            throw e;
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    private long filesUnder(Fixture f) throws IOException {
        Path staging = tempDir.resolve(f.tenantId() + "-staging");
        if (!Files.exists(staging)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(staging)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private long keyRows(Fixture f) {
        return jdbc.queryForObject("select count(*) from storage_object_keys where tenant_id = ?", Long.class,
                f.tenantId());
    }

    private long bytesHeld(ImportItem item) throws IOException {
        StorageLocation location = storageLocationRepository.findById(item.getStagedStorageLocationId()).orElseThrow();
        try (InputStream in = storage.resolve(location).download(location, item.getStagedObjectKey()).content()) {
            return in.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }

    private static void awaitZero(java.util.function.IntSupplier value, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (value.getAsInt() != 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " stayed at " + value.getAsInt() + " while uploads were stalled");
            }
            Thread.sleep(20);
        }
    }

    private static Throwable cause(Throwable t) {
        return t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.get(60, TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            return cause(e);
        }
    }

    /** A PDF-looking stream that stalls inside the storage upload once it has delivered 12,000 of its 20,000 bytes. */
    private static final class Stalling extends InputStream {
        private final CountDownLatch reached;
        private final CountDownLatch release;
        private final List<Boolean> transactionActiveWhenStalled;
        private int pos;

        Stalling(CountDownLatch reached, CountDownLatch release, List<Boolean> transactionActiveWhenStalled) {
            this.reached = reached;
            this.release = release;
            this.transactionActiveWhenStalled = transactionActiveWhenStalled;
        }

        @Override
        public int read() {
            if (pos >= TOTAL) {
                return -1;
            }
            if (pos == 12_000) {
                transactionActiveWhenStalled.add(TransactionSynchronizationManager.isActualTransactionActive());
                reached.countDown();
                try {
                    if (!release.await(120, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
            int b = pos < head.length ? head[pos] : 'x';
            pos++;
            return b;
        }
    }

    // ------------------------------------------------------------------------------------------------ Test 1 (+ 5, + C)

    @Test
    void manyUploadsStalledInStorage_holdNoTransaction_noRowLock_noConnection_andTheApplicationCarriesOn() throws Exception {
        Fixture f = fixture();
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        int stalledUploads = pool.getMaximumPoolSize() + 2;           // more than the pool could ever hold
        String[] names = new String[stalledUploads + 1];
        for (int i = 0; i < stalledUploads; i++) {
            names[i] = "s" + i + ".pdf";
        }
        names[stalledUploads] = "free.pdf";
        UUID batchA = batchWith(f, names);
        UUID batchB = batchWith(f, "b.pdf");

        CountDownLatch reached = new CountDownLatch(stalledUploads);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        List<Boolean> transactionActiveAtScan = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(call -> {
            transactionActiveAtScan.add(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(scanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        // The stalled uploads block their threads on purpose, so the threads must exist whatever the CPU count: the common
        // pool has about one per CPU, which is too few on a small CI runner. One for each stalled upload plus free.pdf and b.pdf.
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService uploads = Executors.newFixedThreadPool(stalledUploads + 2, runnable -> {
            Thread thread = new Thread(runnable, "bulk-import-it-upload-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        List<CompletableFuture<?>> stalled = new ArrayList<>();
        try (Connection diag = diagnostics()) {
            for (int i = 0; i < stalledUploads; i++) {
                String name = names[i];
                stalled.add(as(f, () -> upload(batchA, name, new Stalling(reached, release, transactionActive), TOTAL), uploads));
            }
            assertThat(reached.await(60, TimeUnit.SECONDS)).as("every upload reached its stall in storage").isTrue();

            // ---- what is held while stalled: nothing
            assertThat(transactionActive).as("no Spring transaction around the storage I/O").hasSize(stalledUploads)
                    .containsOnly(false);
            assertThat(count(diag, "select count(*) from pg_stat_activity where datname = current_database() and "
                    + "state like 'idle in transaction%'")).as("no database session sits in a transaction").isZero();
            assertThat(batchRowIsFree(diag, batchA)).as("the batch row is not locked").isTrue();
            // (a scheduler may borrow a connection for a moment, so wait for the pool to be seen idle - an upload that
            // held one would keep it above zero for as long as the stall lasts)
            awaitZero(() -> pool.getHikariPoolMXBean().getActiveConnections(), "pooled connections in use");
            assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();

            // ---- and the application carries on: an unrelated query, an upload into the same batch (a free row),
            // an upload into another batch - whose encryption key records need their own connections
            assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
            CompletableFuture<Object> sameBatch = as(f, () -> upload(batchA, "free.pdf",
                    new ByteArrayInputStream(file(300)), 300), uploads);
            CompletableFuture<Object> otherBatch = as(f, () -> upload(batchB, "b.pdf",
                    new ByteArrayInputStream(file(300)), 300), uploads);
            sameBatch.get(60, TimeUnit.SECONDS);
            otherBatch.get(60, TimeUnit.SECONDS);
            assertThat(stalled).as("the stalled ones are still stalled").noneMatch(CompletableFuture::isDone);
            assertThat(item(batchA, "free.pdf").getStagedSizeBytes()).isEqualTo(300L);
            assertThat(item(batchB, "b.pdf").getStagedSizeBytes()).isEqualTo(300L);
            assertThat(transactionActiveAtScan).as("no Spring transaction around the scan either").hasSize(2)
                    .containsOnly(false);

            // ---- release: every stalled upload finishes - their scans and key lookups all get connections
            release.countDown();
            for (CompletableFuture<?> upload : stalled) {
                upload.get(120, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            uploads.shutdown();
            if (!uploads.awaitTermination(60, TimeUnit.SECONDS)) {
                uploads.shutdownNow();                  // a wedged upload is interrupted rather than left to leak its thread
            }
        }
        assertThat(uploads.isTerminated()).as("no upload thread outlives the test").isTrue();
        for (int i = 0; i < stalledUploads; i++) {
            assertThat(item(batchA, names[i]).getStagedSizeBytes()).isEqualTo((long) TOTAL);
        }
        assertThat(items.sumStagedBytes(batchA)).isEqualTo((long) TOTAL * stalledUploads + 300);
        assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
    }

    // ------------------------------------------------------------------------------------------------ Test 2

    @Test
    void twoUploadsThatTogetherExceedTheCap_bothFinishTheirIO_exactlyOneIsRecorded_theOtherIsCleanedUp() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        UUID batch = batchWith(f, "a.pdf", "b.pdf");
        long filesBefore = filesUnder(f);
        long keysBefore = keyRows(f);

        CountDownLatch bothScanned = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(call -> {
            Object result = call.callRealMethod();         // the file is stored and clean ...
            bothScanned.countDown();
            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();   // ... and now waits for the other one
            return result;
        }).when(scanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        CompletableFuture<Object> first = as(f, () -> upload(batch, "a.pdf", new ByteArrayInputStream(file(700 * 1024)),
                700 * 1024));
        CompletableFuture<Object> second = as(f, () -> upload(batch, "b.pdf", new ByteArrayInputStream(file(700 * 1024)),
                700 * 1024));
        assertThat(bothScanned.await(60, TimeUnit.SECONDS)).as("both passed the preliminary check and did their I/O")
                .isTrue();
        assertThat(filesUnder(f) - filesBefore).as("both objects exist").isEqualTo(2);

        release.countDown();                                // both go to finalise at the same moment
        Throwable failureOfFirst = failureOf(first);
        Throwable failureOfSecond = failureOf(second);

        assertThat(List.of(failureOfFirst == null, failureOfSecond == null)).as("exactly one was recorded")
                .containsExactlyInAnyOrder(true, false);
        Throwable refused = failureOfFirst != null ? failureOfFirst : failureOfSecond;
        assertThat(refused).isInstanceOf(ImportBatchSizeException.class);
        assertThat(items.sumStagedBytes(batch)).as("the cap holds").isEqualTo(700L * 1024).isLessThanOrEqualTo(MIB);
        assertThat(filesUnder(f) - filesBefore).as("the refused attempt's object was removed").isEqualTo(1);
        assertThat(keyRows(f) - keysBefore).as("and its key record").isEqualTo(1);
        ImportItem winner = items.findAllByImportBatchIdOrderByRowNumberAsc(batch).stream()
                .filter(i -> i.getStagedObjectKey() != null).findFirst().orElseThrow();
        assertThat(bytesHeld(winner)).isEqualTo(700L * 1024);
    }

    @Test
    void twoUploadsForTheSameRow_exactlyOneWins_andTheLoserRemovesOnlyItsOwnObject() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf");
        long filesBefore = filesUnder(f);
        CountDownLatch bothScanned = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(call -> {
            Object result = call.callRealMethod();
            bothScanned.countDown();
            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(scanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        CompletableFuture<Object> first = as(f, () -> upload(batch, "a.pdf", new ByteArrayInputStream(file(2000)), 2000));
        CompletableFuture<Object> second = as(f, () -> upload(batch, "a.pdf", new ByteArrayInputStream(file(3000)), 3000));
        assertThat(bothScanned.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(filesUnder(f) - filesBefore).as("each attempt has its own object").isEqualTo(2);

        release.countDown();
        List<Boolean> succeeded = List.of(failureOf(first) == null, failureOf(second) == null);

        assertThat(succeeded).containsExactlyInAnyOrder(true, false);
        assertThat(filesUnder(f) - filesBefore).as("only the winner's object is left").isEqualTo(1);
        ImportItem a = item(batch, "a.pdf");
        assertThat(bytesHeld(a)).as("and it is the one the row points at").isEqualTo(a.getStagedSizeBytes());
    }

    // ------------------------------------------------------------------------------------------------ Test 3 and 4

    private CompletableFuture<Object> uploadPausedBeforeItIsRecorded(Fixture f, UUID batch, String name, int size,
                                                                     CountDownLatch stored, CountDownLatch release) {
        Mockito.doAnswer(call -> {
            Object result = call.callRealMethod();
            stored.countDown();                              // stored and clean, not yet recorded
            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(scanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());
        return as(f, () -> upload(batch, name, new ByteArrayInputStream(file(size)), size));
    }

    @Test
    void cancelCommitsBeforeTheUploadIsRecorded_theUploadIsRefused_andItsObjectRemoved() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf");
        long filesBefore = filesUnder(f);
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> upload = uploadPausedBeforeItIsRecorded(f, batch, "a.pdf", 4000, stored, release);
        assertThat(stored.await(60, TimeUnit.SECONDS)).isTrue();

        TenantContext.set(f.tenantId(), f.userId());
        bulk.cancel(batch);                                   // commits while the upload is between its phases
        release.countDown();

        assertThat(failureOf(upload)).isInstanceOf(ImportBatchStateException.class);
        assertThat(batches.findById(batch).orElseThrow().getStatus()).isEqualTo(ImportBatch.ImportBatchStatus.cancelled);
        assertThat(item(batch, "a.pdf").getStagedObjectKey()).isNull();
        assertThat(filesUnder(f)).as("the attempt's object is gone").isEqualTo(filesBefore);
    }

    @Test
    void theUploadIsRecordedBeforeCancel_cancelDeletesIt_andTheUploadsOwnCleanupDidNotTouchIt() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf");
        long filesBefore = filesUnder(f);

        TenantContext.set(f.tenantId(), f.userId());
        upload(batch, "a.pdf", new ByteArrayInputStream(file(4000)), 4000);
        ImportItem recorded = item(batch, "a.pdf");
        assertThat(bytesHeld(recorded)).as("the accepted object is there").isEqualTo(4000L);
        assertThat(filesUnder(f) - filesBefore).isEqualTo(1);

        bulk.cancel(batch);                                   // sees the committed item and deletes its object

        assertThat(item(batch, "a.pdf").getStagedObjectKey()).isNull();
        assertThat(filesUnder(f)).as("deleted by the cancel's own clean-up").isEqualTo(filesBefore);
    }

    @Test
    void submitCommitsBeforeTheUploadIsRecorded_theUploadIsRefused_andItsObjectRemoved() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf", "b.pdf");
        long filesBefore = filesUnder(f);
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> upload = uploadPausedBeforeItIsRecorded(f, batch, "a.pdf", 4000, stored, release);
        assertThat(stored.await(60, TimeUnit.SECONDS)).isTrue();

        TenantContext.set(f.tenantId(), f.userId());
        bulk.submit(batch);                                   // commits while the upload is between its phases
        release.countDown();

        assertThat(failureOf(upload)).isInstanceOf(ImportBatchStateException.class);
        assertThat(batches.findById(batch).orElseThrow().getStatus()).isEqualTo(ImportBatch.ImportBatchStatus.queued);
        assertThat(items.countReceived(batch)).as("nothing was appended to the submitted batch").isZero();
        assertThat(filesUnder(f)).isEqualTo(filesBefore);
    }

    @Test
    void theUploadIsRecordedBeforeSubmit_theSubmittedBatchContainsIt() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf", "b.pdf");
        TenantContext.set(f.tenantId(), f.userId());

        upload(batch, "a.pdf", new ByteArrayInputStream(file(4000)), 4000);
        bulk.submit(batch);

        assertThat(batches.findById(batch).orElseThrow().getStatus()).isEqualTo(ImportBatch.ImportBatchStatus.queued);
        assertThat(items.countReceived(batch)).as("the worker sees the committed item").isEqualTo(1);
        assertThat(bytesHeld(item(batch, "a.pdf"))).isEqualTo(4000L);
    }

    // ------------------------------------------------------------------------------------------------ Test 7

    /** The real EICAR test string - every scanner detects it, no real malware involved. As a plain text file: wrapped in a
     *  PDF-looking stream it is not recognised. */
    private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.ISO_8859_1);

    @Test
    void infectedContent_isRefused_leavesNoObject_andALaterCleanFileForTheSameRowIsAccepted() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.txt");
        long filesBefore = filesUnder(f);
        long keysBefore = keyRows(f);
        TenantContext.set(f.tenantId(), f.userId());

        assertThatThrownBy(() -> upload(batch, "a.txt", new ByteArrayInputStream(EICAR), EICAR.length))
                .isInstanceOf(ImportFileBlockedException.class);

        assertThat(item(batch, "a.txt").getStagedObjectKey()).isNull();
        assertThat(item(batch, "a.txt").getIntakeBlockedReason()).as("the refusal is recorded on the row").isNotNull();
        assertThat(filesUnder(f)).isEqualTo(filesBefore);
        assertThat(keyRows(f)).isEqualTo(keysBefore);

        byte[] clean = "plain and harmless text".repeat(20).getBytes(StandardCharsets.ISO_8859_1);
        upload(batch, "a.txt", new ByteArrayInputStream(clean), clean.length);
        assertThat(item(batch, "a.txt").getStagedSizeBytes()).isEqualTo((long) clean.length);
        assertThat(item(batch, "a.txt").getIntakeBlockedReason()).as("cleared by the clean file").isNull();
    }

    @Test
    void aStreamThatFailsInStorage_leavesNoObjectAndNoRecord_andTheRowCanBeUploadedAgain() throws Exception {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf");
        long filesBefore = filesUnder(f);
        long keysBefore = keyRows(f);
        TenantContext.set(f.tenantId(), f.userId());
        AtomicBoolean failed = new AtomicBoolean();
        InputStream failing = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent >= 5000) {
                    failed.set(true);
                    throw new IOException("connection reset");
                }
                byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
                return sent < head.length ? head[sent++] : (sent++ & 0x7f);
            }
        };

        assertThatThrownBy(() -> upload(batch, "a.pdf", failing, 9000)).isInstanceOf(RuntimeException.class);

        assertThat(failed).isTrue();
        assertThat(filesUnder(f)).isEqualTo(filesBefore);
        assertThat(keyRows(f)).isEqualTo(keysBefore);
        assertThat(item(batch, "a.pdf").getStagedObjectKey()).isNull();
        assertThat(items.sumStagedBytes(batch)).isZero();

        upload(batch, "a.pdf", new ByteArrayInputStream(file(500)), 500);
        assertThat(item(batch, "a.pdf").getStagedSizeBytes()).isEqualTo(500L);
    }

    @Test
    void aCallerThatIsAlreadyInATransaction_isRefusedBeforeAnyStorageIsTouched() {
        Fixture f = fixture();
        UUID batch = batchWith(f, "a.pdf");
        long keysBefore = keyRows(f);
        TenantContext.set(f.tenantId(), f.userId());

        assertThatThrownBy(() -> transactions.execute(status -> upload(batch, "a.pdf",
                new ByteArrayInputStream(file(500)), 500)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must not run inside a transaction");

        assertThat(keyRows(f)).isEqualTo(keysBefore);
        assertThat(item(batch, "a.pdf").getStagedObjectKey()).isNull();
    }
}
