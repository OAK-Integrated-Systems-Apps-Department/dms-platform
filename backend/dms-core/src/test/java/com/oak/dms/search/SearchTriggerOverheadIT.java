package com.oak.dms.search;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What the search triggers cost on a bulk update (C3): a real 5,000-document ownership transfer through the API, run with
 * the {@code documents} update trigger enabled and then disabled, alternating, and the median of each reported. The same
 * 5,000 rows are also updated directly in SQL for the cases the real transfer cannot isolate: an update that touches only
 * unwatched columns (must cost nothing and enqueue nothing), one that changes only {@code updated_at} (the open question
 * on whether it stays a watched column), and one that lands on documents that already have a pending job (coalescing).
 *
 * <p>The trigger is switched off with {@code ALTER TABLE ... DISABLE TRIGGER} and always switched back on in {@code finally}
 * (and before each test): this runs against the disposable test database, never anything else. Results are printed and
 * written to {@code target/search-trigger-overhead.txt}.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchTriggerOverheadIT extends com.oak.dms.ownership.OwnershipApiSupportBridge {

    private static final int DOCUMENTS = 5000;
    private static final int ROUNDS = 3;
    private static final String UPDATE_TRIGGER = "trg_search_documents_upd";

    private final List<String> report = new ArrayList<>();

    @AfterEach
    void enableTriggerAndPublish() throws IOException {
        fx.jdbc().execute("alter table documents enable trigger " + UPDATE_TRIGGER);
        report.forEach(line -> System.out.println("[search-trigger-overhead] " + line));
        Path out = Path.of("target", "search-trigger-overhead.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private record World(UUID tenant, User admin, User owner, User successor) {
    }

    /** A tenant with exactly {@code DOCUMENTS} documents owned by one person, and an empty search queue. */
    private World world() {
        UUID tenant = fx.tenant();
        UUID type = fx.documentType(tenant, "Contract");
        User admin = fx.user(tenant, "Admin");
        User owner = fx.user(tenant, "Owner");
        User successor = fx.user(tenant, "Successor");
        fx.role(tenant, "Viewers", new String[]{"docm.documents.view"}, owner, successor);
        UUID seed = fx.document(tenant, type, owner, "Seed");
        bulkDocuments(tenant, type, owner, versionOf(seed), DOCUMENTS - 1, "Bench");
        fx.jdbc().update("delete from search_index_jobs where tenant_id = ?", tenant);
        return new World(tenant, admin, owner, successor);
    }

    private long jobs(UUID tenant) {
        return fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenant);
    }

    private static double millis(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1e6;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private double transfer(World w) {
        return millis(() -> {
            try {
                JsonNode summary = call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(w.admin()), allOf(w.owner(), w.successor()), 200);
                assertThat(summary.get("transferredCount").asInt()).isEqualTo(DOCUMENTS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void aRealFiveThousandDocumentOwnershipTransferWithAndWithoutTheTriggers() {
        List<Double> on = new ArrayList<>();
        List<Double> off = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            World a = world();
            fx.jdbc().execute("alter table documents enable trigger " + UPDATE_TRIGGER);
            on.add(transfer(a));
            assertThat(jobs(a.tenant())).as("one coalesced job per transferred document, no more").isEqualTo(DOCUMENTS);
            assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ? and reason = 'owner'", a.tenant()))
                    .as("each job says why").isEqualTo(DOCUMENTS);

            World b = world();
            fx.jdbc().execute("alter table documents disable trigger " + UPDATE_TRIGGER);
            try {
                off.add(transfer(b));
            } finally {
                fx.jdbc().execute("alter table documents enable trigger " + UPDATE_TRIGGER);
            }
            assertThat(jobs(b.tenant())).as("triggers off: nothing enqueued").isZero();
        }
        double medianOn = median(on);
        double medianOff = median(off);
        report.add(String.format("real ownership transfer of %d documents (median of %d, alternating):", DOCUMENTS, ROUNDS));
        report.add(String.format("  triggers ON : %8.0f ms   runs %s", medianOn, fmt(on)));
        report.add(String.format("  triggers OFF: %8.0f ms   runs %s", medianOff, fmt(off)));
        report.add(String.format("  overhead    : %8.0f ms  = %.1f%% of the transfer, %.3f ms per document",
                medianOn - medianOff, 100 * (medianOn - medianOff) / medianOff, (medianOn - medianOff) / DOCUMENTS));
        // A generous guard, not a target: the trigger must not turn a transfer into something qualitatively slower.
        assertThat(medianOn).as("transfer with triggers (%.0f ms) vs without (%.0f ms)", medianOn, medianOff)
                .isLessThan(medianOff * 2 + 5_000);
    }

    @Test
    void sqlLevelCases_unwatchedColumnsCostNothing_updatedAtAloneEnqueues_andCoalescingIsCheap() {
        World w = world();
        UUID tenant = w.tenant();

        // 1. only unwatched columns (check-out bookkeeping): the WHEN clause is false, so no job and ~no cost
        double unwatchedOn = timedUpdate(tenant, true,
                "update documents set checked_out_by = ?, checked_out_at = now(), checkout_expires_at = now() + interval '1 hour' where tenant_id = ?", w.owner().getUserId());
        assertThat(jobs(tenant)).as("unwatched columns enqueue nothing").isZero();
        double unwatchedOff = timedUpdate(tenant, false,
                "update documents set checked_out_by = ?, checked_out_at = now(), checkout_expires_at = now() + interval '1 hour' where tenant_id = ?", w.owner().getUserId());
        report.add(String.format("5,000 rows, unwatched columns only   : ON %6.0f ms, OFF %6.0f ms, jobs 0", unwatchedOn, unwatchedOff));

        // 2. updated_at alone: the open question
        double touchOn = timedUpdate(tenant, true, "update documents set updated_at = now() + interval '1 second', created_at = created_at where tenant_id = ? and ?::int > 0", 1);
        long touchJobs = jobs(tenant);
        fx.jdbc().update("delete from search_index_jobs where tenant_id = ?", tenant);
        double touchOff = timedUpdate(tenant, false, "update documents set updated_at = now() + interval '2 second', created_at = created_at where tenant_id = ? and ?::int > 0", 1);
        report.add(String.format("5,000 rows, updated_at alone         : ON %6.0f ms, OFF %6.0f ms, jobs %d", touchOn, touchOff, touchJobs));
        assertThat(touchJobs).isEqualTo(DOCUMENTS);

        // 3. a watched change on documents that already have a pending job (coalescing into the existing row)
        fx.jdbc().update("delete from search_index_jobs where tenant_id = ?", tenant);
        timedUpdate(tenant, true, "update documents set title = title || '!' where tenant_id = ? and ?::int > 0", 1);
        long fresh = jobs(tenant);
        double coalesce = timedUpdate(tenant, true, "update documents set title = title || '?' where tenant_id = ? and ?::int > 0", 1);
        long afterCoalesce = jobs(tenant);
        fx.jdbc().update("delete from search_index_jobs where tenant_id = ?", tenant);
        double fresh2 = timedUpdate(tenant, true, "update documents set title = title || '.' where tenant_id = ? and ?::int > 0", 1);
        report.add(String.format("5,000 rows, new job rows (insert)    : %6.0f ms, jobs %d", fresh2, fresh));
        report.add(String.format("5,000 rows, coalescing (upsert)      : %6.0f ms, jobs %d (unchanged)", coalesce, afterCoalesce));
        assertThat(fresh).isEqualTo(DOCUMENTS);
        assertThat(afterCoalesce).as("coalescing keeps exactly one row per document").isEqualTo(DOCUMENTS);

        // which application paths touch updated_at WITHOUT touching another watched column (found by reading the code):
        report.add("updated_at-only writers: signature lock/unlock (DocumentSignatureLockService) and access-mode changes "
                + "(DocumentAccessService.setAccessMode); every other path that sets it (new version, move, delete, restore) "
                + "also changes a watched column and would fire the trigger regardless.");
    }

    /**
     * One update statement run against the tenant's 5,000 documents with the update trigger on or off; returns
     * milliseconds. Every statement is written with the tenant as its first placeholder and, where it needs one, a dummy
     * second argument; the check-out statement binds the owner first and the tenant second.
     */
    private double timedUpdate(UUID tenant, boolean triggerOn, String sql, Object argument) {
        fx.jdbc().execute("alter table documents " + (triggerOn ? "enable" : "disable") + " trigger " + UPDATE_TRIGGER);
        try {
            return millis(() -> {
                int updated = sql.startsWith("update documents set checked_out_by = ?")
                        ? fx.jdbc().update(sql, argument, tenant)
                        : fx.jdbc().update(sql, tenant, argument);
                assertThat(updated).isEqualTo(DOCUMENTS);
            });
        } finally {
            fx.jdbc().execute("alter table documents enable trigger " + UPDATE_TRIGGER);
        }
    }

    private static String fmt(List<Double> values) {
        return values.stream().map(v -> String.format("%.0f", v)).toList().toString();
    }
}
