package com.oak.dms.ownership;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O12: a 500-document transfer in the normal run - set-based statements, one audit event, one summary. The
 * 10,000-document run is {@link OwnershipScaleIT} (opt-in, {@code -Pperf}).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipPerformanceIT extends OwnershipApiSupport {

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User successor;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        successor = fx.user(tenantId, "Successor");
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, successor);
    }

    @Test
    void fiveHundredDocumentsMoveInOneRunWithOneAuditEventAndOneSummary() throws Exception {
        UUID ref = versionOf(fx.document(tenantId, type, owner, "Seed"));
        bulkDocuments(tenantId, type, owner, ref, 499, "Perf");

        long started = System.nanoTime();
        JsonNode summary = call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin), allOf(owner, successor), 200);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(summary.get("transferredCount").asInt()).isEqualTo(500);
        assertThat(millis).as("500 documents took %d ms", millis).isLessThan(15_000);
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", tenantId)).isEqualTo(500);
        assertThat(auditCount(tenantId, "OWNERSHIP_BATCH_TRANSFERRED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "DOCUMENT_OWNERSHIP_TRANSFERRED")).isZero();
        assertThat(fx.count("select count(*) from notifications where tenant_id = ? and category_code = 'document.ownership.received'", tenantId))
                .isEqualTo(1);
        assertThat(ownedBy(owner)).isZero();
    }

    @Test
    void fiveHundredDocumentsInARestrictedFolderAreConvertedInOneRun() throws Exception {
        UUID folder = folder(tenantId, "Closed", true, "edit", owner);
        UUID ref = versionOf(fx.document(tenantId, type, owner, "Seed"));
        bulkDocuments(tenantId, type, owner, ref, 499, "Closed");
        fx.jdbc().update("update documents set folder_id = ? where tenant_id = ?", folder, tenantId);

        long started = System.nanoTime();
        JsonNode summary = call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin), allOf(owner, successor), 200);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(summary.get("transferredCount").asInt()).isEqualTo(500);
        assertThat(summary.get("convertedToRestrictedCount").asInt()).isEqualTo(500);
        assertThat(summary.get("accessGrantedCount").asInt()).isEqualTo(500);
        assertThat(millis).as("500 conversions took %d ms", millis).isLessThan(20_000);
        assertThat(fx.count("select count(*) from documents where tenant_id = ? and access_mode = 'restricted'", tenantId)).isEqualTo(500);
        // one entry each for the new owner (manage) and the folder's one member (copied)
        assertThat(fx.count("select count(*) from document_access where tenant_id = ?", tenantId)).isEqualTo(1000);
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ? and access_granted", tenantId)).isEqualTo(500);
    }
}
