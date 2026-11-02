package com.oak.dms.ownership;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.relationship.seed.RelationshipTypeSeeder;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ownership changes racing other writers (O12): a transfer against a relationship being created on the same
 * documents, two transfers of one document, overlapping bulk runs, a double offboarding, and a transfer against a
 * soft delete or a permanent delete of the same document. Run five times in a row after any change to the locking
 * or the transfer code.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipConcurrencyIT extends OwnershipApiSupport {

    private static final int ROUNDS = 5;

    @MockitoBean
    KeycloakUserService keycloak;

    @Autowired
    RelationshipTypeSeeder typeSeeder;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    TransactionTemplate transactions;

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User successor;
    private User third;
    private User linker;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeSeeder.install(tenantId);
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        successor = fx.user(tenantId, "Successor");
        third = fx.user(tenantId, "Third");
        linker = fx.user(tenantId, "Linker");
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, successor, third);
        fx.role(tenantId, "Linker", new String[]{"drm.manage-document-relationships.create", VIEW}, linker);
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        when(keycloak.getPlatformRealmRoles(admin.getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
    }

    /** Runs all the requests at the same moment (a latch releases every thread together); returns their statuses. */
    private List<Integer> race(List<Callable<Integer>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> action : actions) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return action.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();
        return statuses;
    }

    private int status(MockHttpServletRequestBuilder request, User as, boolean tenantAdmin, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request.with(tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as))).andReturn().getResponse().getStatus();
    }

    /** Like a client would: the other writer may be the deadlock victim (a retryable 409) - ask again. */
    private int statusRetrying(MockHttpServletRequestBuilder request, User as, boolean tenantAdmin, Object body) throws Exception {
        int status = status(request, as, tenantAdmin, body);
        for (int i = 0; i < 3 && status == 409; i++) {
            status = status(request, as, tenantAdmin, body);
        }
        return status;
    }

    private JsonNode bodyOf(MockHttpServletRequestBuilder request, User as, Object body) throws Exception {
        request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request.with(fx.tenantAdminJwtFor(as))).andReturn().getResponse().getContentAsString());
    }

    private Callable<Integer> bulk(List<UUID> ids, User target) {
        return () -> status(post("/api/v1/ownership/transfers"), admin, true, selected(ids, target));
    }

    @Test
    void aTransferRacingARelationshipOnTheSameDocumentsBothSucceed() throws Exception {
        UUID relType = fx.jdbc().queryForObject("select relationship_type_id from relationship_types where tenant_id = ? and code = 'RELATED'",
                UUID.class, tenantId);
        for (int round = 0; round < ROUNDS; round++) {
            UUID a = fx.document(tenantId, type, owner, "Left " + round);
            UUID b = fx.document(tenantId, type, owner, "Right " + round);
            List<Integer> statuses = race(List.of(
                    bulk(List.of(a, b), successor),
                    () -> status(post("/api/v1/document-relationships"), linker, false,
                            body("relationshipTypeId", relType, "sourceDocumentId", a, "targetDocumentId", b))));
            assertThat(statuses).as("round " + round).containsExactly(200, 201);
            assertThat(ownerOf(a)).isEqualTo(successor.getUserId().toString());
            assertThat(ownerOf(b)).isEqualTo(successor.getUserId().toString());
            // RELATED is symmetric, so the pair is stored in canonical (lower id first) order
            assertThat(fx.count("select count(*) from document_relationships where tenant_id = ? and removed_at is null"
                    + " and ((source_document_id = ? and target_document_id = ?) or (source_document_id = ? and target_document_id = ?))",
                    tenantId, a, b, b, a)).isEqualTo(1);
            assertThat(historyRows(tenantId, a)).isEqualTo(1);
        }
    }

    @Test
    void twoTransfersOfTheSameDocumentAreSerialisedWithAnUnbrokenChain() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID doc = fx.document(tenantId, type, owner, "Contested " + round);
            List<Integer> statuses = race(List.of(bulk(List.of(doc), successor), bulk(List.of(doc), third)));
            assertThat(statuses).as("round " + round).containsExactly(200, 200);
            var rows = fx.jdbc().queryForList("select previous_owner_user_id, new_owner_user_id from ownership_transfers"
                    + " where document_id = ? order by transferred_at, transfer_id", doc);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("previous_owner_user_id")).isEqualTo(owner.getUserId());
            assertThat(rows.get(1).get("previous_owner_user_id")).as("the second move starts where the first ended")
                    .isEqualTo(rows.get(0).get("new_owner_user_id"));
            assertThat(ownerOf(doc)).isEqualTo(rows.get(1).get("new_owner_user_id").toString());
        }
    }

    @Test
    void overlappingBulkRunsDoNotDeadlockAndEveryDocumentEndsWhereTheLastHistoryRowSays() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            List<UUID> docs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                docs.add(fx.document(tenantId, type, owner, "Overlap " + round + "-" + i));
            }
            List<Integer> statuses = race(List.of(bulk(docs.subList(0, 30), successor), bulk(docs.subList(10, 40), third)));
            assertThat(statuses).as("round " + round).containsExactly(200, 200);
            assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ? and document_id in"
                    + " (select document_id from documents where tenant_id = ? and title like ?)",
                    tenantId, tenantId, "Overlap " + round + "-%")).isEqualTo(60);
            for (UUID doc : docs) {
                String last = fx.jdbc().queryForObject("select new_owner_user_id::text from ownership_transfers where document_id = ?"
                        + " order by transferred_at desc, transfer_id desc limit 1", String.class, doc);
                assertThat(ownerOf(doc)).isEqualTo(last);
            }
        }
    }

    @Test
    void doubleOffboardingOfOneUserOffboardsOnceAndTheOtherIsNotFound() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            User leaver = fx.user(tenantId, "Leaver" + round);
            fx.document(tenantId, type, leaver, "Leaver doc " + round);
            var request = body("reason", "Left", "mode", "transfer", "newOwnerUserId", successor.getUserId());
            List<Integer> statuses = race(List.of(
                    () -> status(post("/api/v1/users/" + leaver.getUserId() + "/offboard"), admin, true, request),
                    () -> status(post("/api/v1/users/" + leaver.getUserId() + "/offboard"), admin, true, request)));
            assertThat(statuses.stream().sorted().toList()).as("round " + round).containsExactly(200, 404);
            assertThat(fx.count("select count(*) from ownership_transfer_batches where tenant_id = ? and source_user_id = ?",
                    tenantId, leaver.getUserId())).isEqualTo(1);
            assertThat(fx.count("select count(*) from users where user_id = ? and is_deleted", leaver.getUserId())).isEqualTo(1);
            verify(keycloak, times(1)).disableUser(leaver.getKeycloakId());
        }
    }

    @Test
    void aTransferRacingASoftDeleteStillTransfersAndTheOwnerIsNotRolledBack() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID doc = fx.document(tenantId, type, owner, "Binned in a race " + round);
            List<Integer> statuses = race(List.of(
                    () -> status(post("/api/v1/documents/" + doc + "/ownership/transfer"), admin, true, body("newOwnerUserId", successor.getUserId())),
                    () -> statusRetrying(delete("/api/v1/documents/" + doc), admin, true, null)));
            assertThat(statuses.get(0)).as("transfer, round " + round).isEqualTo(200);
            assertThat(statuses.get(1)).as("soft delete, round " + round).isIn(200, 204);
            assertThat(fx.count("select count(*) from documents where document_id = ? and is_deleted", doc)).isEqualTo(1);
            assertThat(ownerOf(doc)).as("a soft-deleted document is still transferred").isEqualTo(successor.getUserId().toString());
            assertThat(historyRows(tenantId, doc)).isEqualTo(1);
        }
    }

    @Test
    void aTransferRacingAPermanentDeleteSkipsThePurgedDocumentWithoutError() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID doc = fx.document(tenantId, type, owner, "Purged in a race " + round);
            fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", doc);
            List<Integer> statuses = race(List.of(
                    () -> status(post("/api/v1/ownership/transfers"), admin, true, selected(List.of(doc), successor)),
                    () -> statusRetrying(delete("/api/v1/recycle-bin/" + doc), admin, true, null)));
            assertThat(statuses.get(0)).as("bulk transfer, round " + round).isEqualTo(200);
            assertThat(statuses.get(1)).as("purge, round " + round).isIn(200, 204);
            assertThat(fx.count("select count(*) from documents where document_id = ? and purged_at is not null", doc)).isEqualTo(1);
            // either the transfer got there first (one history row, new owner) or the purge did (skipped, nothing written)
            long rows = historyRows(tenantId, doc);
            assertThat(rows).isIn(0L, 1L);
            assertThat(ownerOf(doc)).isEqualTo((rows == 1 ? successor : owner).getUserId().toString());
            long skippedPurged = fx.count("select count(*) from ownership_transfer_batches where tenant_id = ? and skipped_summary ? 'purged'"
                    .replace("?", "?").replace("skipped_summary ? 'purged'", "skipped_summary @> '{\"purged\":1}'::jsonb"), tenantId);
            assertThat(skippedPurged).isLessThanOrEqualTo(round + 1L);
            fx.jdbc().update("delete from ownership_transfer_batches where false");   // (append-only history; batches are left)
        }
    }

    @Test
    void aSingleTransferRacingAPermanentDeleteIsEitherDoneOrNotFoundNeverAnError() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID doc = fx.document(tenantId, type, owner, "Single purged in a race " + round);
            fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", doc);
            List<Integer> statuses = race(List.of(
                    () -> status(post("/api/v1/documents/" + doc + "/ownership/transfer"), admin, true, body("newOwnerUserId", successor.getUserId())),
                    () -> statusRetrying(delete("/api/v1/recycle-bin/" + doc), admin, true, null)));
            assertThat(statuses.get(0)).as("transfer, round " + round).isIn(200, 404);
            assertThat(statuses.get(1)).as("purge, round " + round).isIn(200, 204);
            assertThat(historyRows(tenantId, doc)).isEqualTo(statuses.get(0) == 200 ? 1 : 0);
        }
    }

    @Test
    void aStalePersistenceContextCannotWriteTheOldOwnerBack() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "Stale copy");
        transactions.executeWithoutResult(status -> {
            Document stale = documentRepository.findById(doc).orElseThrow();       // loaded while owner is still the old one
            try {
                ExecutorService other = Executors.newSingleThreadExecutor();
                int result = other.submit(() -> status(post("/api/v1/documents/" + doc + "/ownership/transfer"), admin, true,
                        body("newOwnerUserId", successor.getUserId()))).get();
                other.shutdown();
                assertThat(result).isEqualTo(200);                                  // committed in its own transaction
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            stale.setUpdatedAt(OffsetDateTime.now());                               // an ordinary edit through the entity
            documentRepository.saveAndFlush(stale);
        });
        assertThat(ownerOf(doc)).as("only the changed column is written").isEqualTo(successor.getUserId().toString());
    }
}
