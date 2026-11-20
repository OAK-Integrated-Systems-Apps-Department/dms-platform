package com.oak.dms.search.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent updates to the index: many writers on one document and several indexer loops at once (as with more than one
 * dms-core instance) must converge on the database's final state, with an empty queue, no failed job and no duplicate or
 * missing document. Run five times in a row (plan rule 5).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchConcurrencyIT extends SearchApiSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String indexedTitle(UUID document) throws Exception {
        engine.refresh(indexAdmin.writeAlias());
        JsonNode body = JSON.readTree("{\"query\":{\"ids\":{\"values\":[\"" + document + "\"]}},\"_source\":[\"title\"]}");
        JsonNode hits = engine.search(indexAdmin.readAlias(tenantId), tenantId.toString(), body).path("hits").path("hits");
        return hits.isEmpty() ? null : hits.get(0).path("_source").path("title").asText();
    }

    private void drainUntilEmpty(ExecutorService pool, int loops) throws Exception {
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < loops; i++) {
            all.add(pool.submit(() -> {
                int idle = 0;
                while (idle < 3) {
                    if (indexer.runOnce() == 0) {
                        idle++;
                        Thread.sleep(30);
                    } else {
                        idle = 0;
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : all) {
            f.get(120, TimeUnit.SECONDS);
        }
    }

    @Test
    void manyWritersOnOneDocumentWithIndexersRunningConvergeOnTheFinalTitle() throws Exception {
        UUID document = doc("Original title", "o.txt", "body");
        index();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            AtomicBoolean writing = new AtomicBoolean(true);
            // two indexer loops run the whole time the writers are busy
            List<Future<?>> indexers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                indexers.add(pool.submit(() -> {
                    while (writing.get()) {
                        if (indexer.runOnce() == 0) {
                            Thread.sleep(10);
                        }
                    }
                    return null;
                }));
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> writers = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                int writer = w;
                Callable<Void> task = () -> {
                    go.await();
                    for (int i = 0; i < 25; i++) {
                        fx.jdbc().update("update documents set title = ? where document_id = ?", "Title w" + writer + " n" + i, document);
                    }
                    return null;
                };
                writers.add(pool.submit(task));
            }
            go.countDown();
            for (Future<?> f : writers) {
                f.get(120, TimeUnit.SECONDS);
            }
            writing.set(false);
            for (Future<?> f : indexers) {
                f.get(60, TimeUnit.SECONDS);
            }
            drainUntilEmpty(pool, 2);
        } finally {
            pool.shutdownNow();
        }
        String finalTitle = fx.jdbc().queryForObject("select title from documents where document_id = ?", String.class, document);
        assertThat(indexedTitle(document)).as("the index converged on the database").isEqualTo(finalTitle);
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenantId)).as("queue empty").isZero();
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ? and status = 'failed'", tenantId)).isZero();
    }

    @Test
    void severalIndexerLoopsShareManyDocumentsWithoutLossOrDuplicates() throws Exception {
        UUID seed = doc("Seed", "s.txt", "seed body");
        UUID version = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, seed);
        index();
        int n = 150;
        fx.jdbc().update("""
                insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, created_at, updated_at)
                select gen_random_uuid(), ?, ?, 'Parallel ' || g, ?, ?, now(), now() from generate_series(1, ?) g
                """, tenantId, reportType, version, alice.getUserId(), n);
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenantId)).isEqualTo(n);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            drainUntilEmpty(pool, 4);
        } finally {
            pool.shutdownNow();
        }
        engine.refresh(indexAdmin.writeAlias());
        long indexed = engine.count(indexAdmin.writeAlias(), tenantId.toString(),
                JSON.readTree("{\"term\":{\"tenant_id\":\"" + tenantId + "\"}}"));
        assertThat(indexed).as("seed + every generated document, once each").isEqualTo(n + 1L);
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenantId)).isZero();
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ? and status = 'failed'", tenantId)).isZero();
        fx.jdbc().execute("alter table documents disable trigger trg_search_documents_upd");
        fx.jdbc().execute("alter table documents disable trigger trg_search_documents_ins");
        try {
            fx.jdbc().update("delete from documents where tenant_id = ? and title like 'Parallel %'", tenantId);
        } finally {
            fx.jdbc().execute("alter table documents enable trigger trg_search_documents_ins");
            fx.jdbc().execute("alter table documents enable trigger trg_search_documents_upd");
        }
    }
}
