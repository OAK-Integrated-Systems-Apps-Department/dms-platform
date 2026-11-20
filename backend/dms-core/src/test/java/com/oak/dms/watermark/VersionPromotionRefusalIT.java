package com.oak.dms.watermark;

import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.user.User;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOC-06 end to end through the real ingestion gate: real scan (ClamAV, as in {@code DocumentUploadIT}), real storage,
 * real promoter and processor. The document is put into the state under test AFTER the request-time checks would have
 * passed - the gate is entered directly, as the editor, signature placement and template replacement enter it, and the
 * gate itself looks at no document state - so what is exercised is the promotion-time refusal.
 * <p>
 * A refusal must be terminal and cheap: the ticket says {@code failed} with the real reason, the file is scanned
 * <em>once</em> (never again on a retry), nothing is promoted, the attempt's staged file is gone and it left no stray
 * object in the document's storage, the failure is audited, and the document and its content are untouched.
 */
@SpringBootTest
class VersionPromotionRefusalIT extends WatermarkFixtures {

    private static final String SCAN_TIMER = "dms.document.ingestion";

    @Autowired private IngestionGate ingestionGate;
    @Autowired private DocumentService documentService;
    @Autowired private MeterRegistry meters;

    private IngestionTicketResponse submit(Fixture f) {
        byte[] file = ("%PDF-1.4\n% new version " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.ISO_8859_1);
        return ingestionGate.submit(IngestionRequest.newVersion(f.documentId(), "replacement_upload", "memo.pdf",
                file.length, new ByteArrayInputStream(file), "a change"));
    }

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

    private Path documentStore(Fixture f) {
        return tempDir.resolve(f.tenantId().toString());
    }

    private Path stagingStore(Fixture f) {
        return tempDir.resolve(f.tenantId() + "-staging");
    }

    private Map<String, Object> scanRow(UUID scanResultId) {
        return jdbc.queryForMap("select status, failure_reason, attempt_count, next_attempt_at, completed_at, "
                + "staging_purged_at, document_version_id from scan_results where scan_result_id = ?", scanResultId);
    }

    private String documentState(Fixture f) {
        return jdbc.queryForObject("""
                select (select count(*) from document_versions where document_id = d.document_id) || '|'
                    || d.current_version_id || '|' || d.latest_version_id || '|' || d.legal_hold || '|'
                    || coalesce(d.signature_locked_at::text, '-') || '|' || d.is_deleted
                  from documents d where d.document_id = ?
                """, String.class, f.documentId());
    }

    private long scanFailedAudits(Fixture f) {
        return auditCountForTenant(f, "DOCUMENT_SCAN_FAILED");
    }

    private long auditCountForTenant(Fixture f, String action) {
        Integer n = jdbc.queryForObject("select count(*) from audit_events where tenant_id = ? and action = ?",
                Integer.class, f.tenantId(), action);
        return n == null ? 0 : n;
    }

    /** Submits, then checks everything a terminal refusal must (and must not) have done. */
    private void assertRefusedTerminally(Fixture f, String code, String messageFragment) throws IOException {
        String stateBefore = documentState(f);
        long scansBefore = scans();
        long documentFiles = filesUnder(documentStore(f));
        long stagingFiles = filesUnder(stagingStore(f));
        long failuresBefore = scanFailedAudits(f);

        IngestionTicketResponse ticket = submit(f);

        assertThat(ticket.status()).isEqualTo(ScanResultStatus.failed);
        assertThat(ticket.retrying()).isFalse();
        assertThat(ticket.message()).startsWith("The file passed the malware scan but was not added: ")
                .contains(messageFragment).doesNotContain("could not be scanned");
        assertThat(ticket.versionId()).as("nothing was promoted").isNull();

        Map<String, Object> row = scanRow(ticket.scanResultId());
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat((String) row.get("failure_reason")).startsWith("promotion-refused:" + code + ":");
        assertThat(((Number) row.get("attempt_count")).intValue()).as("no failed attempt was recorded for a retry").isZero();
        assertThat(row.get("next_attempt_at")).as("nothing is scheduled for a retry").isNull();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("document_version_id")).isNull();
        assertThat(row.get("staging_purged_at")).as("the attempt's staged file was purged").isNotNull();

        assertThat(scans() - scansBefore).as("the file was scanned once and never again").isEqualTo(1);
        assertThat(filesUnder(stagingStore(f))).as("staging is back to what it was").isEqualTo(stagingFiles);
        assertThat(filesUnder(documentStore(f))).as("no copy was left in the document's storage").isEqualTo(documentFiles);
        assertThat(scanFailedAudits(f) - failuresBefore).as("one DOCUMENT_SCAN_FAILED audit event").isEqualTo(1);
        assertThat(documentState(f)).as("versions, current/latest, hold, lock and deletion are untouched").isEqualTo(stateBefore);
    }

    private byte[] downloadCurrent(Fixture f) throws IOException {
        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        try (InputStream in = downloaded.content()) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ control

    @Test
    void anOrdinaryDocument_takesTheNewVersion_asBefore() throws Exception {
        Fixture f = fixture();
        long scansBefore = scans();

        IngestionTicketResponse ticket = submit(f);

        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);
        assertThat(ticket.versionId()).isNotNull();
        assertThat(jdbc.queryForObject("select current_version_id from documents where document_id = ?", UUID.class,
                f.documentId())).isEqualTo(ticket.versionId());
        assertThat(scans() - scansBefore).isEqualTo(1);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aLockAppliedAfterAcceptance_endsTheIngestion_withoutRetryOrRescan_andTheDocumentIsUntouched() throws Exception {
        Fixture f = fixture();
        jdbc.update("update documents set signature_locked_at = now(), signature_locked_version_id = current_version_id "
                + "where document_id = ?", f.documentId());

        assertRefusedTerminally(f, "DOCUMENT_SIGNATURE_LOCKED", "locked because it carries a valid signature");
        assertThat(downloadCurrent(f)).as("the existing content is still served").isEqualTo(minimalPdf());
    }

    @Test
    void aLegalHold_endsTheIngestion_withoutRetryOrRescan() throws Exception {
        Fixture f = fixture();
        jdbc.update("update documents set legal_hold = true, legal_hold_at = now(), legal_hold_by = ?, "
                + "legal_hold_reason = 'test' where document_id = ?", f.userId(), f.documentId());

        assertRefusedTerminally(f, "DOCUMENT_LEGAL_HOLD", "placed under legal hold");
        assertThat(downloadCurrent(f)).isEqualTo(minimalPdf());
    }

    @Test
    void aDeletedDocument_isAnExplicitTerminalRefusal_notTenGenericRescans() throws Exception {
        Fixture f = fixture();
        jdbc.update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", f.documentId());

        assertRefusedTerminally(f, "DOCUMENT_UNAVAILABLE", "was deleted while the file was being scanned");
    }

    @Test
    void aVersionSignedAfterAcceptance_isNotReplaced() throws Exception {
        Fixture f = fixture();
        jdbc.update("update document_versions set created_reason = 'signed_copy' where version_id = ?", f.versionId());

        assertRefusedTerminally(f, "DOCUMENT_HAS_SIGNATURE", "was signed while the file was being scanned");
        assertThat(downloadCurrent(f)).isEqualTo(minimalPdf());
    }

    // ------------------------------------------------------------------ DOC-07: the check-out, through the real gate

    private void checkOut(Fixture f, UUID holder, String taken, String expires) {
        jdbc.update("update documents set checked_out_by = ?, checked_out_at = " + taken + ", checkout_expires_at = "
                + expires + " where document_id = ?", holder, f.documentId());
    }

    @Test
    void aCheckOutHeldBySomeoneElse_endsTheIngestionTerminally_withoutRescan() throws Exception {
        Fixture f = fixture();
        User bob = userRepository.save(User.builder().tenantId(f.tenantId()).keycloakId(UUID.randomUUID())
                .email(UUID.randomUUID() + "@doc07.invalid").username("bob-" + UUID.randomUUID()).firstName("B")
                .lastName("T").displayName("Bob").status(User.UserStatus.active).build());
        checkOut(f, bob.getUserId(), "now() - interval '1 hour'", "now() + interval '70 hours'");

        assertRefusedTerminally(f, "DOCUMENT_CHECKED_OUT", "checked out by someone else");
        assertThat(jdbc.queryForObject("select checked_out_by from documents where document_id = ?", UUID.class,
                f.documentId())).as("nobody's check-out was released").isEqualTo(bob.getUserId());
    }

    @Test
    void theUploadersOwnCheckOutTakenBeforeTheSubmission_promotes_andIsNotReleased() throws Exception {
        Fixture f = fixture();
        checkOut(f, f.userId(), "now() - interval '2 hours'", "now() + interval '70 hours'");

        IngestionTicketResponse ticket = submit(f);

        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);
        assertThat(jdbc.queryForObject("select current_version_id from documents where document_id = ?", UUID.class,
                f.documentId())).isEqualTo(ticket.versionId());
        assertThat(jdbc.queryForObject("select checked_out_by from documents where document_id = ?", UUID.class,
                f.documentId())).as("promotion does not release the check-out").isEqualTo(f.userId());
    }

    @Test
    void aCheckOutTakenAfterTheUploadWasSubmitted_isNotTheLeaseItWasSubmittedUnder() throws Exception {
        Fixture f = fixture();
        // a lease stamped in the future stands in for "taken after the file was accepted" (the gate stamps submission now)
        checkOut(f, f.userId(), "now() + interval '10 minutes'", "now() + interval '70 hours'");

        assertRefusedTerminally(f, "DOCUMENT_CHECKED_OUT", "checked out again after this file was submitted");
    }

    @Test
    void theUploadersLeaseThatExpiredWhileTheFileWasScanned_isRefused_notAuthorised() throws Exception {
        Fixture f = fixture();
        checkOut(f, f.userId(), "now() - interval '3 hours'", "now() - interval '1 hour'");

        assertRefusedTerminally(f, "CHECKOUT_EXPIRED", "expired while the file was being scanned");
    }

    @Test
    void anotherTenantsRequest_neverReachesPromotion_theTenantScopedKeyStopsItFirst_andTheDocumentIsUntouched() throws Exception {
        Fixture owner = fixture();
        String before = documentState(owner);
        Fixture outsider = fixture(); // leaves the context on the outsider's tenant

        byte[] file = ("%PDF-1.4\n% other tenant " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.ISO_8859_1);
        // The scan result's (tenant, target document) key is tenant-scoped, so the row for another tenant's document
        // cannot even be created: existing isolation, in place before - and independent of - the promotion-time check.
        assertThatThrownBy(() -> ingestionGate.submit(IngestionRequest.newVersion(owner.documentId(),
                "replacement_upload", "memo.pdf", file.length, new ByteArrayInputStream(file), "x")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(documentState(owner)).isEqualTo(before);
        assertThat(outsider.tenantId()).isNotEqualTo(owner.tenantId());
    }
}
