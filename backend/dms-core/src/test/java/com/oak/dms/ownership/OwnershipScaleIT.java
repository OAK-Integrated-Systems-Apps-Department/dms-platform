package com.oak.dms.ownership;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 10,000 documents in two requests of 5,000 (the most one request takes). Slow and opt-in: left out of the normal
 * {@code verify}; run with {@code ./mvnw verify -Pperf -Dit.test=OwnershipScaleIT}.
 */
@Tag("perf")
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipScaleIT extends OwnershipApiSupport {

    private UUID tenantId;
    private User admin;
    private User owner;
    private User successor;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        successor = fx.user(tenantId, "Successor");
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, successor);
    }

    @Test
    void tenThousandDocumentsInTwoRequests() throws Exception {
        UUID type = fx.documentType(tenantId, "Contract");
        UUID ref = versionOf(fx.document(tenantId, type, owner, "Seed"));
        bulkDocuments(tenantId, type, owner, ref, 9_999, "Scale");
        assertThat(ownedBy(owner)).isEqualTo(10_000);

        for (int request = 1; request <= 2; request++) {
            long started = System.nanoTime();
            var summary = call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin),
                    allOf(owner, successor, "limit", 5000), 200);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(summary.get("transferredCount").asInt()).isEqualTo(5000);
            assertThat(millis).as("request %d took %d ms", request, millis).isLessThan(60_000);
        }
        assertThat(ownedBy(owner)).isZero();
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", tenantId)).isEqualTo(10_000);
        assertThat(auditCount(tenantId, "OWNERSHIP_BATCH_TRANSFERRED")).isEqualTo(2);
    }
}
