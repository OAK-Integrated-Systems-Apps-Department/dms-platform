package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** NFR-001: the Related Documents panel with 100 relationships loads within 2 seconds. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipPerformanceIT extends RelationshipApiSupport {

    @Test
    void panelWith100Relationships_loadsWithinTwoSeconds() throws Exception {
        UUID tenantId = tenantWithTypes();
        User user = fx.user(tenantId, "Editor");
        fx.role(tenantId, "Editor", new String[]{CREATE, REMOVE, VIEW}, user);
        UUID type = fx.documentType(tenantId, "Contract");
        UUID hub = fx.document(tenantId, type, user, "Hub");
        List<UUID> types = new ArrayList<>();
        for (String code : List.of("SUPERSEDES", "MAIN_ATTACHMENT", "REPORT_EVIDENCE", "RELATED", "DUPLICATE_OF")) {
            types.add(typeId(tenantId, code));
        }
        for (int i = 0; i < 100; i++) {
            UUID other = fx.document(tenantId, type, user, "Other " + i);
            UUID t = types.get(i % types.size());
            // Half point at the hub, half away from it (a two-way type is stored lower id first).
            UUID source = i % 2 == 0 ? hub : other;
            UUID target = i % 2 == 0 ? other : hub;
            if (i % types.size() == 3 && source.toString().compareTo(target.toString()) > 0) {
                UUID swap = source;
                source = target;
                target = swap;
            }
            fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                    + " target_document_id, created_by) values (?, ?, ?, ?, ?)", tenantId, t, source, target, user.getUserId());
        }
        panel(user, hub);   // warm the pool and the JIT; the measured call is the second

        long start = System.nanoTime();
        JsonNode p = panel(user, hub);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(p.get("total").asInt()).isEqualTo(100);
        assertThat(p.get("groups")).hasSize(5);
        assertThat(millis).as("panel with 100 relationships took %d ms", millis).isLessThan(2000);
    }
}
