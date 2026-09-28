package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentMetadataResponse;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the confidence gate against the REAL Spring context and the real
 * dockerized Postgres (no mocks) -- the {@code ocrFieldsRaw} map passed to
 * {@link DocumentMetadataService#applyOcrFields} below is byte-for-byte the
 * shape a live ocr-worker actually returned for a genuinely low-confidence
 * field during manual verification (a "description" long_text field, no
 * label match in the document, forced through the always-low_confidence
 * free-text fallback -- see extraction.py's _extract_field_value): {@code
 * {"description": {"value": "...", "confidence": 0.5, "bbox": null,
 * "status": "low_confidence"}}}.
 * <p>
 * Uses raw JdbcTemplate for storage_locations/document_versions: this
 * branch has no Java entity for storage_locations (a different, simpler
 * ObjectStorageService than the pluggable-storage feature's), so
 * documents.latest_version_id's deferred FK into document_versions can
 * only be satisfied here via a direct insert, not a repository.
 * <p>
 * Deliberately NOT {@code @Transactional} at the class level:
 * {@link DocumentMetadataServiceImpl#applyOcrFields} is itself {@code
 * REQUIRES_NEW} (see its own doc comment), which would suspend a
 * class-level test transaction and run against a brand new connection --
 * one that, under Postgres MVCC, cannot see this test's own not-yet-
 * committed setup rows from an ambient transaction. Fixture rows are
 * genuinely committed in {@link #setUp} instead, and removed by hand in
 * {@link #tearDown}.
 */
@SpringBootTest
class OcrConfidenceGateIT {

    @Autowired
    private DocumentMetadataService documentMetadataService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID userId;
    private UUID documentTypeId;
    private UUID documentId;
    private UUID versionId;
    private UUID descriptionFieldId;

    @BeforeEach
    void setUp() {
        // saveAndFlush, not save: the next statements are raw JdbcTemplate
        // inserts in the SAME transaction that FK-reference these rows --
        // without an explicit flush, Hibernate's write-behind batching
        // hasn't actually issued the INSERT yet, and Postgres's FK check
        // (not deferred, unlike documents/document_versions' own) sees no
        // matching tenant/user row.
        Tenant tenant = tenantRepository.saveAndFlush(Tenant.builder()
                .tenantName("OCR Confidence Gate IT Tenant " + UUID.randomUUID())
                .domain("ocr-confidence-gate-it-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        tenantId = tenant.getTenantId();

        UUID keycloakId = UUID.randomUUID();
        User admin = userRepository.saveAndFlush(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@ocr-confidence-gate-it.invalid")
                .username(keycloakId.toString())
                .firstName("OCR").lastName("Gate")
                .displayName("OCR Confidence Gate IT")
                .status(User.UserStatus.active)
                .build());
        userId = admin.getUserId();
        TenantContext.set(tenantId, userId, admin.getDisplayName(), keycloakId);

        documentTypeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO document_types (document_type_id, tenant_id, type_code, type_name, created_by) VALUES (?, ?, ?, ?, ?)",
                documentTypeId, tenantId, "OCR_GATE_IT", "OCR Gate IT Type", userId);

        descriptionFieldId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO metadata_fields (field_id, tenant_id, field_key, label, data_type, is_ocr_extractable, status, created_by) " +
                        "VALUES (?, ?, 'description', 'Description', 'long_text', true, 'active', ?)",
                descriptionFieldId, tenantId, userId);
        jdbcTemplate.update(
                "INSERT INTO document_type_fields (tenant_id, document_type_id, field_id, schema_version, status, is_mandatory, display_order, created_by) " +
                        "VALUES (?, ?, ?, 1, 'active', false, 1, ?)",
                tenantId, documentTypeId, descriptionFieldId, userId);

        UUID storageLocationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO storage_locations (storage_location_id, tenant_id, name, provider, base_path, status, created_by) " +
                        "VALUES (?, ?, 'OCR Gate IT Location', 'FILE_SYSTEM', '/tmp/ocr-gate-it', 'active', ?)",
                storageLocationId, tenantId, userId);

        // documents.latest_version_id <-> document_versions.document_id is
        // a mutual NOT NULL FK pair, both DEFERRABLE INITIALLY DEFERRED --
        // deferred only applies within ONE transaction. Without an ambient
        // @Transactional on this test (see class doc comment on why),
        // each separate jdbcTemplate.update() auto-commits on its own, so
        // the deferred check would fire (and fail) at the first INSERT's
        // own implicit commit. One statement (a CTE), same as
        // scripts/dev/smoke_test_ocr.py's bootstrap_fixture, avoids that.
        documentId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        jdbcTemplate.update(
                "WITH new_document AS (" +
                        "  INSERT INTO documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, created_by) " +
                        "  VALUES (?, ?, ?, 'OCR confidence gate IT document', ?, ?, ?) RETURNING document_id" +
                        ") " +
                        "INSERT INTO document_versions (version_id, document_id, tenant_id, version_number, major_version, minor_version, " +
                        "  storage_location_id, storage_object_key, original_filename, mime_type, file_hash, size_bytes, created_reason, " +
                        "  scan_status, ocr_status, created_by) " +
                        "SELECT ?, document_id, ?, 1, 1, 0, ?, 'ocr-gate-it/version', 'test.pdf', 'application/pdf', 'test-hash', 100, " +
                        "  'initial_upload', 'clean', 'completed', ? " +
                        "FROM new_document",
                documentId, tenantId, documentTypeId, versionId, userId, userId,
                versionId, tenantId, storageLocationId, userId);
    }

    @AfterEach
    void tearDown() {
        // Real deletes, not a rolled-back transaction (see class doc
        // comment on why this isn't @Transactional) -- FK-safe order,
        // children before parents.
        // documents <-> document_versions is a mutual FK (same DEFERRABLE
        // pair as setUp's own CTE insert) -- with no ambient transaction,
        // two separate jdbcTemplate.update() deletes each auto-commit on
        // their own, so whichever runs first always violates the other's
        // still-deferred-but-now-checked-at-that-statement's-own-commit
        // constraint. One multi-statement execute() (a real transaction
        // block, same as psql's own multi-statement -c behavior) deletes
        // both before either commits.
        jdbcTemplate.update("DELETE FROM document_metadata_values WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM document_ocr_field_results WHERE document_id = ?", documentId);
        jdbcTemplate.execute(String.format(
                "DELETE FROM documents WHERE document_id = '%s'; DELETE FROM document_versions WHERE document_id = '%s';",
                documentId, documentId));
        jdbcTemplate.update("DELETE FROM document_type_fields WHERE document_type_id = ?", documentTypeId);
        jdbcTemplate.update("DELETE FROM metadata_fields WHERE field_id = ?", descriptionFieldId);
        jdbcTemplate.update("DELETE FROM storage_locations WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM document_types WHERE document_type_id = ?", documentTypeId);
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
    }

    @Test
    void aLowConfidenceOcrResultIsPersistedAndFlaggedNeedsReviewThroughTheRealStack() {
        // Exact shape captured live from a running ocr-worker container
        // (POST /ocr/process/{version_id} with requested_fields=[{field_key:
        // "description", data_type: "long_text"}] against tests/fixtures/
        // native_text.pdf, which has no "description" label anywhere, so it
        // fell through to the always-low_confidence free-text summary).
        // bbox omitted (not null-in-a-Map.of, which throws NPE) -- it's
        // null in the real payload too (a free-text-fallback value has no
        // single spatial location) and applyOcrFields never reads it anyway.
        Map<String, Object> ocrFieldsRaw = Map.of(
                "description", Map.of(
                        "value", "Oak Coffee Roasters\nInvoice No: INV-77001 Invoice Date: 2026-05-01 Bill To: Acme Corp Description Amount Espresso Beans 5kg 210.00 Shipping 15.00 Total Due: 225.00",
                        "confidence", 0.5,
                        "status", "low_confidence"));

        documentMetadataService.applyOcrFields(documentId, ocrFieldsRaw);

        DocumentMetadataResponse response = documentMetadataService.getValues(documentId);
        assertThat(response.values()).containsKey("description");
        assertThat(response.needsReview()).containsExactly("description");

        Boolean needsReviewInDb = jdbcTemplate.queryForObject(
                "SELECT needs_review FROM document_metadata_values WHERE document_id = ? AND field_id = ?",
                Boolean.class, documentId, descriptionFieldId);
        assertThat(needsReviewInDb).isTrue();

        BigDecimal confidenceInDb = jdbcTemplate.queryForObject(
                "SELECT confidence_score FROM document_metadata_values WHERE document_id = ? AND field_id = ?",
                BigDecimal.class, documentId, descriptionFieldId);
        assertThat(confidenceInDb).isEqualByComparingTo("0.5");
    }

    @Test
    void aManualEditClearsAPreviouslyFlaggedNeedsReview() {
        documentMetadataService.applyOcrFields(documentId, Map.of(
                "description", Map.of("value", "auto-guess", "confidence", 0.5, "status", "low_confidence")));
        assertThat(documentMetadataService.getValues(documentId).needsReview()).containsExactly("description");

        documentMetadataService.saveValues(documentId, Map.of("description", "the real, human-confirmed description"), false);

        DocumentMetadataResponse response = documentMetadataService.getValues(documentId);
        assertThat(response.values().get("description")).isEqualTo("the real, human-confirmed description");
        assertThat(response.needsReview()).isEmpty();
    }

    /**
     * Regression test: found live during manual browser verification.
     * {@link DocumentServiceImpl#getOcrStatus} has its own SEPARATE
     * re-sync of document_metadata_values from document_ocr_field_results
     * (a cold-start-race safety net -- see that method's own doc comment),
     * which runs on every poll once ocr_status is "completed", not just
     * once. Before this fix it built its ocrFieldsRaw map with only
     * {@code value}/{@code confidence}, never {@code status} -- so every
     * poll after the first silently cleared needs_review back to false,
     * even though document_ocr_field_results.status genuinely said
     * "low_confidence" the whole time.
     */
    @Test
    void getOcrStatusReSyncPreservesNeedsReviewNotJustApplyOcrFields() {
        jdbcTemplate.update("UPDATE document_versions SET ocr_status = 'completed' WHERE version_id = ?", versionId);
        jdbcTemplate.update(
                "INSERT INTO document_ocr_field_results (field_result_id, tenant_id, document_id, version_id, field_key, value, confidence, status) " +
                        "VALUES (gen_random_uuid(), ?, ?, ?, 'description', 'auto-guess from re-sync path', 0.5, 'low_confidence')",
                tenantId, documentId, versionId);

        documentService.getOcrStatus(documentId);

        assertThat(documentMetadataService.getValues(documentId).needsReview()).containsExactly("description");
    }
}
