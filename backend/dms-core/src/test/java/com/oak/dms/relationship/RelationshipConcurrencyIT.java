package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Writers racing each other (R5): opposite Supersedes at the same moment, a ring of four links that
 * share no document pairwise, and the same link twice. Run five times in a row after any change to
 * the locking or the loop check.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipConcurrencyIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User[] users;
    private UUID[] docs;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        UUID type = fx.documentType(tenantId, "Policy");
        users = new User[4];
        for (int i = 0; i < users.length; i++) {
            users[i] = fx.user(tenantId, "User" + i);
            fx.role(tenantId, "Editor" + i, new String[]{CREATE, REMOVE, VIEW}, users[i]);
        }
        docs = new UUID[4];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = fx.document(tenantId, type, users[0], "Doc " + i);
        }
    }

    /** POSTs all the links at the same moment (a latch releases every thread together) and returns the statuses. */
    private List<Integer> race(UUID typeId, List<UUID[]> links) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(links.size());
        CountDownLatch ready = new CountDownLatch(links.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            UUID[] link = links.get(i);
            User as = users[i % users.length];
            Callable<Integer> call = () -> {
                ready.countDown();
                go.await();
                MvcResult result = mvc.perform(post("/api/v1/document-relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("relationshipTypeId", typeId, "sourceDocumentId", link[0],
                                "targetDocumentId", link[1])))
                        .with(fx.jwtFor(as))).andReturn();
                return result.getResponse().getStatus();
            };
            futures.add(pool.submit(call));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        return statuses;
    }

    @Test
    void oppositeSupersedesAtOnce_exactlyOneWins() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        List<Integer> statuses = race(supersedes, List.of(new UUID[]{docs[0], docs[1]}, new UUID[]{docs[1], docs[0]}));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(activeLinks(tenantId)).isEqualTo(1);
    }

    @Test
    void aRingOfFour_neverClosesTheLoop() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        // 0->1, 1->2, 2->3, 3->0: AB and CD share no document, nor do BC and DA.
        List<Integer> statuses = race(supersedes, List.of(new UUID[]{docs[0], docs[1]}, new UUID[]{docs[1], docs[2]},
                new UUID[]{docs[2], docs[3]}, new UUID[]{docs[3], docs[0]}));
        assertThat(statuses).filteredOn(s -> s == 201).hasSizeBetween(3, 3);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(1);
        assertThat(activeLinks(tenantId)).isEqualTo(3);
    }

    @Test
    void theSameLinkTwice_oneCreatedOneRefused() throws Exception {
        UUID related = typeId(tenantId, "RELATED");
        List<Integer> statuses = race(related, List.of(new UUID[]{docs[0], docs[1]}, new UUID[]{docs[1], docs[0]},
                new UUID[]{docs[0], docs[1]}));
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(2);
        assertThat(activeLinks(tenantId)).isEqualTo(1);
    }
}
