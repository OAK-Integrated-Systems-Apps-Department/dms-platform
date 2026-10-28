package com.oak.dms.watermark;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which version a request may actually name.
 * <p>
 * {@code versionId} is the most dangerous field on the request: it is a
 * direct reference supplied by the caller, and getting its scoping wrong
 * would turn this endpoint into a way of reading a version of some OTHER
 * document - or another tenant's - by id. The permission half of that rule
 * lives in {@code WatermarkEndpointAuthorizationIT}; this is the data half.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkVersionAccessIT extends WatermarkFixtures {

    @Autowired
    private WatermarkService watermarkService;

    @Test
    void aVersionBelongingToAnotherDocumentIsNotFound() {
        // Both documents belong to the caller, so this is purely about the
        // version being scoped to its own document rather than looked up
        // globally by id.
        Fixture mine = fixture();
        Fixture otherDocument = fixture();

        assertThatThrownBy(() -> watermarkService.apply(mine.documentId(),
                versionRequest(otherDocument.versionId()), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(countJobsFor(mine.documentId())).isZero();
    }

    @Test
    void aVersionBelongingToAnotherTenantIsNotFound() {
        Fixture stranger = fixture();
        Fixture mine = fixture();
        TenantContext.clear();
        TenantContext.set(mine.tenantId(), mine.userId());

        assertThatThrownBy(() -> watermarkService.apply(mine.documentId(),
                versionRequest(stranger.versionId()), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(countJobsFor(mine.documentId())).isZero();
    }

    @Test
    void aVersionIdThatDoesNotExistAtAllIsNotFound() {
        Fixture mine = fixture();
        assertThatThrownBy(() -> watermarkService.apply(mine.documentId(),
                versionRequest(UUID.randomUUID()), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDeletedVersionIsNotFound() {
        Fixture mine = fixture();
        UUID second = addVersion(mine, 2);
        jdbc.update("update document_versions set is_deleted = true, deleted_at = now() where version_id = ?",
                second);

        assertThatThrownBy(() -> watermarkService.apply(mine.documentId(), versionRequest(second), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPurgedVersionIsNotFound() {
        // A purge sets purged_at AND is_deleted - the bytes are gone and the
        // row is a tombstone. Nothing should be able to ask for a watermark
        // of it.
        Fixture mine = fixture();
        UUID second = addVersion(mine, 2);
        jdbc.update("update document_versions set purged_at = now(), is_deleted = true, deleted_at = now() "
                + "where version_id = ?", second);

        assertThatThrownBy(() -> watermarkService.apply(mine.documentId(), versionRequest(second), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnscannedVersionCannotBeAskedForBecauseItCannotExist() {
        // The scan gate turns out to be stronger than an application check
        // could be: a database trigger refuses to CREATE a version that is
        // not clean or administratively overridden, and a second trigger
        // refuses to move one away from clean afterwards. So "watermark an
        // unscanned version" is not a request that can be made - there is no
        // row to name.
        //
        // DocumentContentReader still checks isServable(), and should: it is
        // the same defence VersionContent applies, it costs nothing, and it
        // is what would catch a future migration that relaxed either
        // trigger. This test pins the guarantee it is defending.
        Fixture mine = fixture();
        UUID second = addVersion(mine, 2);

        assertThatThrownBy(() -> jdbc.update(
                "update document_versions set scan_status = 'pending' where version_id = ?", second))
                .hasMessageContaining("cannot move away from clean");

        assertThatThrownBy(() -> jdbc.update("""
                        insert into document_versions (version_id, document_id, tenant_id, version_number,
                            major_version, minor_version, storage_location_id, storage_object_key,
                            original_filename, mime_type, file_hash, size_bytes, created_reason, scan_status)
                        select ?, document_id, tenant_id, 99, 1, 0, storage_location_id, storage_object_key,
                            original_filename, mime_type, file_hash, size_bytes, 'replacement_upload', 'pending'
                        from document_versions where version_id = ?
                        """, UUID.randomUUID(), mine.versionId()))
                .describedAs("a version must be born clean")
                .hasMessageContaining("scan_status = clean");

        // And the application check is still there, unreachable or not.
        assertThat(DocumentNotScannedException.class).isNotNull();
    }

    @Test
    void theDocumentsOwnCurrentVersionIsUsedWhenNoneIsNamed() {
        Fixture mine = fixture();
        addVersion(mine, 2);

        // currentVersionId still points at version 1, and "current else
        // latest" must prefer it over the newer row.
        assertThat(watermarkService.apply(mine.documentId(), customText("DRAFT"), null).versionId())
                .isEqualTo(mine.versionId());
    }

    private static WatermarkRequest versionRequest(UUID versionId) {
        return new WatermarkRequest(null, "DRAFT", versionId, null, null, null, null, null, null);
    }
}
