package com.oak.dms.editing;

import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DOC-12: heap use of a WOPI save no longer depends on its size. The controller and the spool are measured on their own
 * (the ingestion gate is a stub that drains the stream, so any remaining allocation is the controller's and the spool's,
 * not the gate's) with the same measure the investigation used: bytes allocated by the request's own thread (exact).
 * Before the fix a 64 MiB save allocated 128.4 MiB and a chunked 201 MiB request 400.9 MiB; eight concurrent 64 MiB saves
 * under a 900 MiB heap ran out of memory.
 * <p>
 * Run this class with a small heap ({@code JAVA_TOOL_OPTIONS=-Xmx900m}) to repeat the original failure scenario.
 */
@SpringBootTest(properties = {
        "dms.editing.collabora-url=http://localhost:9980",
        "dms.editing.token-secret=an-editing-it-secret-that-is-only-for-tests-0123456789",
        "dms.editing.spool-dir=${java.io.tmpdir}/oak-dms-wopi-spool-memory-it"
})
@Import(WorkflowFixtures.class)
class WopiSaveMemoryIT extends WorkflowApiSupport {

    private static final long MIB = 1024 * 1024;
    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    /** Generous: the buffers are 8 KiB; everything else (token, lookups, audit) is a few hundred KiB. */
    private static final long BOUND = 4 * MIB;

    @MockitoBean EditingEngine engine;
    @MockitoBean IngestionGate ingestionGate;
    @Autowired WopiTokenCodec tokens;
    @Autowired WopiController controller;
    @Autowired WopiSaveSpool spool;
    @Autowired DataSource dataSource;
    @Autowired Environment env;

    private UUID tenantId;
    private User alice;
    private UUID documentId;
    private volatile Runnable insideTheGate = () -> { };

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        alice = fx.user(tenantId, "Alice");
        documentId = fx.document(tenantId, type, alice, "Master agreement");
        UUID versionId = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?",
                UUID.class, documentId);
        fx.jdbc().update("update document_versions set original_filename = 'Master agreement.docx' where version_id = ?",
                versionId);
        fx.putVersionContent(versionId, "ORIGINAL BYTES".getBytes(StandardCharsets.UTF_8));
        fx.jdbc().update("insert into tenant_platform_settings (tenant_id, online_editing_allowed) values (?, true) "
                + "on conflict (tenant_id) do update set online_editing_allowed = true", tenantId);
        fx.jdbc().update("insert into tenant_editing_settings (tenant_id, enabled, mode) values (?, true, 'exclusive') "
                + "on conflict (tenant_id) do update set enabled = true, mode = 'exclusive'", tenantId);
        fx.jdbc().update("update documents set checked_out_by = ?, checked_out_at = now(), "
                + "checkout_expires_at = now() + interval '2 hours' where document_id = ?", alice.getUserId(), documentId);
        when(ingestionGate.submit(any())).thenAnswer(call -> {
            IngestionRequest request = call.getArgument(0);
            try (InputStream in = request.content()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            insideTheGate.run();
            return new IngestionTicketResponse(UUID.randomUUID(), ScanResultStatus.pending, documentId, null,
                    "Master agreement.docx", 12L, null, null, null, false, OffsetDateTime.now(), null);
        });
    }

    private String token() {
        return tokens.mint(new WopiTokenCodec.Grant(tenantId, alice.getUserId(), documentId, "Alice",
                Instant.now().plusSeconds(3600)));
    }

    /** A body generated on the fly; optionally stalls half way (to hold many saves mid-body at once). */
    private static final class Generated extends HttpServletRequestWrapper {
        private final long size;
        private final boolean declared;
        private final Runnable atHalfWay;

        Generated(long size, boolean declared, Runnable atHalfWay) {
            super(new MockHttpServletRequest());
            this.size = size;
            this.declared = declared;
            this.atHalfWay = atHalfWay;
        }

        @Override
        public long getContentLengthLong() {
            return declared ? size : -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private long left = size;
                private boolean stalled;

                @Override
                public boolean isFinished() {
                    return left == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }

                @Override
                public int read() {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : one[0];
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (left == 0) {
                        return -1;
                    }
                    if (!stalled && left <= size / 2 && atHalfWay != null) {
                        stalled = true;
                        atHalfWay.run();
                    }
                    int n = (int) Math.min(len, left);
                    java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                    left -= n;
                    return n;
                }
            };
        }
    }

    private record Measured(long allocatedBytes, String outcome) {
    }

    private Measured measure(Callable<?> work) {
        long id = Thread.currentThread().getId();
        long before = TMX.getThreadAllocatedBytes(id);
        String outcome = "saved";
        try {
            work.call();
        } catch (ResponseStatusException e) {
            outcome = String.valueOf(e.getStatusCode().value());
        } catch (Throwable t) {
            outcome = t.getClass().getSimpleName();
        }
        return new Measured(TMX.getThreadAllocatedBytes(id) - before, outcome);
    }

    private static String mib(long bytes) {
        return String.format("%.2f MiB", bytes / (double) MIB);
    }

    // ------------------------------------------------------------------------------------------ allocation

    @Test
    void allocationDoesNotGrowWithTheSizeOfTheSave() {
        long[] sizes = {16 * MIB, 32 * MIB, 64 * MIB};
        long[] allocated = new long[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            long n = sizes[i];
            Measured m = measure(() -> controller.putFile(documentId, token(), null, null, new Generated(n, true, null)));
            allocated[i] = m.allocatedBytes();
            System.out.println("DOC12MEASURE PutFile declared " + mib(n) + " -> " + m.outcome() + ", allocated "
                    + mib(m.allocatedBytes()) + " (was " + mib(2 * n) + " before the fix)");
            assertThat(m.outcome()).isEqualTo("saved");
            assertThat(m.allocatedBytes()).as("bounded, not ~2x the size").isLessThan(BOUND);
        }
        assertThat(allocated[2] - allocated[0]).as("4x the document, no more allocation").isLessThan(MIB);
    }

    @Test
    void aChunkedSave_andAnUndeclaredOversizeRequest_areBoundedToo() {
        long n = 32 * MIB;
        Measured chunked = measure(() -> controller.putFile(documentId, token(), null, null, new Generated(n, false, null)));
        System.out.println("DOC12MEASURE PutFile chunked " + mib(n) + " -> " + chunked.outcome() + ", allocated "
                + mib(chunked.allocatedBytes()) + " (was 66.6 MiB)");
        assertThat(chunked.outcome()).isEqualTo("saved");
        assertThat(chunked.allocatedBytes()).isLessThan(BOUND);

        long over = 201 * MIB;
        Measured declared = measure(() -> controller.putFile(documentId, token(), null, null, new Generated(over, true, null)));
        Measured undeclared = measure(() -> controller.putFile(documentId, token(), null, null, new Generated(over, false, null)));
        System.out.println("DOC12MEASURE PutFile 201 MiB declared -> " + declared.outcome() + ", allocated "
                + mib(declared.allocatedBytes()) + "; chunked -> " + undeclared.outcome() + ", allocated "
                + mib(undeclared.allocatedBytes()) + " (was 400.9 MiB)");
        assertThat(declared.outcome()).isEqualTo("413");
        assertThat(undeclared.outcome()).isEqualTo("413");
        assertThat(declared.allocatedBytes()).isLessThan(MIB);
        assertThat(undeclared.allocatedBytes()).as("the limit is enforced while streaming: ~8 KiB buffers, not 400 MiB")
                .isLessThan(BOUND);
    }

    // ------------------------------------------------------------------------------------------ concurrency, transactions, connections

    private Connection diagnostics() throws SQLException {
        return DriverManager.getConnection(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"));
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void manyConcurrentSaves_allSucceed_hold_noTransaction_noConnection_andAllocateAlmostNothing() throws Exception {
        int saves = Integer.parseInt(System.getenv().getOrDefault("DOC12_K", "8"));
        long n = Long.parseLong(System.getenv().getOrDefault("DOC12_MB", "64")) * MIB;
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        CountDownLatch allMidBody = new CountDownLatch(saves);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        Runnable stall = () -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            allMidBody.countDown();
            try {
                release.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        String token = token();
        ExecutorService executor = Executors.newFixedThreadPool(saves);
        List<Future<Measured>> futures = new ArrayList<>();
        for (int i = 0; i < saves; i++) {
            futures.add(executor.submit(() -> measure(() -> controller.putFile(documentId, token, null, null,
                    new Generated(n, true, stall)))));
        }
        try (Connection diag = diagnostics()) {
            assertThat(allMidBody.await(120, TimeUnit.SECONDS)).as("every save is half way through its body").isTrue();
            // all of them are mid-body at this moment
            assertThat(transactionActive).as("no Spring transaction around the copying").hasSize(saves).containsOnly(false);
            assertThat(count(diag, "select count(*) from pg_stat_activity where datname = current_database() "
                    + "and state like 'idle in transaction%'")).as("no database session sits in a transaction").isZero();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (pool.getHikariPoolMXBean().getActiveConnections() != 0) {   // a scheduler may borrow one for a moment
                assertThat(System.nanoTime()).as("pooled connections in use while saves are mid-body").isLessThan(deadline);
                Thread.sleep(20);
            }
            assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
            assertThat(fx.jdbc().queryForObject("select 1", Integer.class)).as("unrelated queries carry on").isEqualTo(1);
            long onDisk;
            try (var files = java.nio.file.Files.list(spool.directory())) {
                onDisk = files.count();
            }
            assertThat(onDisk).as("one private file per save in progress").isEqualTo(saves);
        } finally {
            release.countDown();
        }
        List<String> outcomes = new ArrayList<>();
        long worst = 0;
        for (Future<Measured> future : futures) {
            Measured m = future.get(180, TimeUnit.SECONDS);
            outcomes.add(m.outcome());
            worst = Math.max(worst, m.allocatedBytes());
        }
        executor.shutdown();
        System.out.println("DOC12MEASURE concurrent saves K=" + saves + " size=" + mib(n) + " heap max="
                + mib(Runtime.getRuntime().maxMemory()) + " outcomes=" + outcomes + " worst per-save allocation="
                + mib(worst) + " (was ~" + mib(2 * n) + " each, 3 of 8 out of memory at 900 MiB)");
        assertThat(outcomes).as("no OutOfMemoryError, no refusals").containsOnly("saved");
        assertThat(worst).isLessThan(BOUND);
        assertThat(java.nio.file.Files.list(spool.directory()).count()).as("every file removed").isZero();
    }

    @Test
    void savesPastTheDiskGuard_areRefusedCleanly_withTheirOwnFilesRemoved() throws Exception {
        // a tiny budget: the guard (not the cap) decides - 507 for a save that cannot fit, nothing left behind
        WopiSaveSpool tight = new WopiSaveSpool(spool.directory().toString(), 4, java.time.Duration.ofMillis(100),
                Long.MAX_VALUE / 2, java.time.Duration.ofHours(1));
        try {
            tight.receive(new java.io.ByteArrayInputStream(new byte[10]), 10, 100);
            org.junit.jupiter.api.Assertions.fail("expected 507");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(507);
        }
        try (var files = java.nio.file.Files.list(spool.directory())) {
            assertThat(files.count()).isZero();
        }
    }
}
