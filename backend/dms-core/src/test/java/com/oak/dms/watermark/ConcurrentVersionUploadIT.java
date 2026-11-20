package com.oak.dms.watermark;

import com.oak.dms.document.DocumentVersionPromoter;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * DOC-08 through the real ingestion gate: two valid uploads of one document that overlap between their {@code prepare}
 * and their promotion - forced deterministically with a barrier after {@code prepare}, so both really are prepared with
 * the same provisional number - must both end {@code clean} with consecutive version numbers. Before the fix the second
 * insert broke {@code uq_document_versions_document_number}, the processor recorded a generic failed attempt, and the
 * file was scanned again later.
 * <p>
 * Real ClamAV, storage and database (like {@code VersionPromotionRefusalIT}). Asserts what a collision used to cost:
 * the scan count (exactly one per file), the attempt count (zero), no scan-failure audit event, no "scan service
 * unavailable" ticket message, nothing left in staging, and exactly the two new files in the document's storage.
 */
@SpringBootTest
class ConcurrentVersionUploadIT extends WatermarkFixtures {

    private static final String SCAN_TIMER = "dms.document.ingestion";

    @Autowired private IngestionGate ingestionGate;
    @Autowired private MeterRegistry meters;
    @MockitoSpyBean private DocumentVersionPromoter promoter;

    private long scans() {
        return meters.find(SCAN_TIMER).timers().stream().mapToLong(Timer::count).sum();
    }

    private long filesUnder(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void twoUploadsOfOneDocument_preparedWithTheSameNumber_bothSucceed_withoutRetryOrRescan() throws Exception {
        Fixture f = fixture();
        long scansBefore = scans();
        long documentFiles = filesUnder(tempDir.resolve(f.tenantId().toString()));

        // Both ingestions finish prepare() - proposing the same next version number - before either goes on to promote.
        CyclicBarrier bothPrepared = new CyclicBarrier(2);
        List<Integer> hints = new java.util.concurrent.CopyOnWriteArrayList<>();
        Mockito.doAnswer(invocation -> {
            Object plan = invocation.callRealMethod();
            hints.add(((com.oak.dms.scanning.service.DocumentPromoter.PromotionPlan) plan).versionNumber());
            bothPrepared.await(60, TimeUnit.SECONDS);
            return plan;
        }).when(promoter).prepare(any());

        CompletableFuture<IngestionTicketResponse> first = CompletableFuture.supplyAsync(() -> submit(f, "first"));
        CompletableFuture<IngestionTicketResponse> second = CompletableFuture.supplyAsync(() -> submit(f, "second"));
        IngestionTicketResponse a = first.get(120, TimeUnit.SECONDS);
        IngestionTicketResponse b = second.get(120, TimeUnit.SECONDS);

        assertThat(hints).as("both preparations proposed the same number").containsExactly(2, 2);
        assertThat(a.status()).as(a.message()).isEqualTo(ScanResultStatus.clean);
        assertThat(b.status()).as(b.message()).isEqualTo(ScanResultStatus.clean);
        assertThat(a.message()).isEqualTo(b.message()).doesNotContain("temporarily unavailable");

        assertThat(jdbc.queryForList("select version_number from document_versions where document_id = ? "
                + "order by version_number", Integer.class, f.documentId())).containsExactly(1, 2, 3);
        // (the fixture's seeded first row carries no label; the two promoted rows follow the usual 1.1 / 1.2)
        assertThat(jdbc.queryForList("select version_label from document_versions where document_id = ? "
                + "and version_number > 1 order by version_number", String.class, f.documentId()))
                .containsExactly("1.1", "1.2");
        assertThat(jdbc.queryForObject("select current_version_id from documents where document_id = ?", UUID.class,
                f.documentId())).isIn(a.versionId(), b.versionId())
                .isEqualTo(jdbc.queryForObject("select latest_version_id from documents where document_id = ?",
                        UUID.class, f.documentId()));

        for (IngestionTicketResponse ticket : List.of(a, b)) {
            var row = jdbc.queryForMap("select status, attempt_count, next_attempt_at, staging_purged_at "
                    + "from scan_results where scan_result_id = ?", ticket.scanResultId());
            assertThat(row.get("status")).isEqualTo("clean");
            assertThat(((Number) row.get("attempt_count")).intValue()).as("no attempt spent on a numbering collision").isZero();
            assertThat(row.get("next_attempt_at")).isNull();
            assertThat(row.get("staging_purged_at")).isNotNull();
        }
        assertThat(scans() - scansBefore).as("each file scanned once - no rescan after a collision").isEqualTo(2);
        Integer failures = jdbc.queryForObject("select count(*) from audit_events where tenant_id = ? "
                + "and action = 'DOCUMENT_SCAN_FAILED'", Integer.class, f.tenantId());
        assertThat(failures).as("no scan-failure audit event").isZero();
        assertThat(filesUnder(tempDir.resolve(f.tenantId() + "-staging"))).as("nothing left in staging").isZero();
        assertThat(filesUnder(tempDir.resolve(f.tenantId().toString())) - documentFiles)
                .as("exactly the two new files, no leaked copy").isEqualTo(2);
    }

    private IngestionTicketResponse submit(Fixture f, String label) {
        TenantContext.set(f.tenantId(), f.userId()); // thread-local: each request thread sets its own
        try {
            byte[] file = ("%PDF-1.4\n% " + label + " " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.ISO_8859_1);
            return ingestionGate.submit(IngestionRequest.newVersion(f.documentId(), "replacement_upload", "memo.pdf",
                    file.length, new ByteArrayInputStream(file), label));
        } finally {
            TenantContext.clear();
        }
    }
}
