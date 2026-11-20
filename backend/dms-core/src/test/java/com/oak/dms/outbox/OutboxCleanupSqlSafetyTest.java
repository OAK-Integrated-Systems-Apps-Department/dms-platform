package com.oak.dms.outbox;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OBX-04: pins the shape of the one statement that deletes outbox rows, without a database - and shows WHY the shape matters by
 * running the same safety check over broader, tempting deletes, every one of which it rejects. The behaviour itself (which rows
 * really go, which stay) is {@code OutboxCleanupIT}'s job on PostgreSQL.
 */
class OutboxCleanupSqlSafetyTest {

    private static String normalised(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static final String CUTOFF = "published_at < now() - make_interval(secs => cast(? as double precision))";

    /** The rules a delete from outbox_events must satisfy to be safe. Returns the rules it breaks. */
    private static List<String> violations(String rawSql) {
        String sql = normalised(rawSql);
        List<String> broken = new ArrayList<>();
        if (!sql.contains("delete from outbox_events")) {
            broken.add("is not a delete from outbox_events");
        }
        int deleteAt = sql.indexOf("delete from outbox_events");
        String deletePart = deleteAt >= 0 ? sql.substring(deleteAt) : sql;
        if (!deletePart.contains("status = 'published'")) {
            broken.add("the DELETE itself does not require status = 'published'");
        }
        if (!deletePart.contains(CUTOFF)) {
            broken.add("the DELETE itself does not require published_at older than the database-derived cutoff");
        }
        if (!sql.contains("for update skip locked")) {
            broken.add("candidates are not row-locked with SKIP LOCKED");
        }
        if (!Pattern.compile("limit \\?").matcher(sql).find()) {
            broken.add("not bounded by a batch limit");
        }
        if (sql.contains("'pending'") || sql.contains("'failed'")) {
            broken.add("mentions pending or failed rows");
        }
        if (sql.contains("<=") || sql.contains(" created_at ")) {
            broken.add("uses a non-strict cutoff or the creation time");
        }
        Matcher cutoffs = Pattern.compile(Pattern.quote(CUTOFF)).matcher(sql);
        int count = 0;
        while (cutoffs.find()) {
            count++;
        }
        if (count < 2) {
            broken.add("the eligibility conditions are not repeated in the DELETE");
        }
        return broken;
    }

    @Test
    void theRealStatementSatisfiesEverySafetyRule() {
        assertThat(violations(OutboxCleanupStore.DELETE_PUBLISHED_BATCH)).isEmpty();
    }

    @Test
    void theStatementDeletesOnlyPublishedRowsOlderThanTheCutoff_checkedAgainInTheDeleteItself() {
        String sql = normalised(OutboxCleanupStore.DELETE_PUBLISHED_BATCH);
        String selectPart = sql.substring(0, sql.indexOf("delete from"));
        String deletePart = sql.substring(sql.indexOf("delete from"));

        for (String part : List.of(selectPart, deletePart)) {
            assertThat(part).contains("status = 'published'").contains(CUTOFF);
        }
    }

    @Test
    void theCutoffIsStrict_aRowExactlyAtTheCutoffIsKept_andComesFromTheDatabaseClock() {
        String sql = normalised(OutboxCleanupStore.DELETE_PUBLISHED_BATCH);

        assertThat(sql).doesNotContain("<=");
        assertThat(sql).contains("published_at < now() - make_interval");
        assertThat(sql).as("no timestamp is passed in from the application").doesNotContain("current_timestamp").doesNotContain("?::timestamp");
    }

    @Test
    void theOrderIsDeterministic_oldestPublishedFirstThenById() {
        String sql = normalised(OutboxCleanupStore.DELETE_PUBLISHED_BATCH);

        assertThat(sql).contains("order by published_at asc, outbox_event_id asc");
        assertThat(sql.indexOf("order by")).isLessThan(sql.indexOf("limit ?"));
        assertThat(sql.indexOf("limit ?")).isLessThan(sql.indexOf("for update skip locked"));
    }

    @Test
    void theCandidateSelectIsEvaluatedOnce_aMaterialisedCteNotARepeatableSubquery() {
        String sql = normalised(OutboxCleanupStore.DELETE_PUBLISHED_BATCH);

        assertThat(sql).startsWith("with picked as materialized (");
        assertThat(sql).contains("using picked p").doesNotContain(" in (select");
    }

    @Test
    void theStoreRefusesToRunWithoutATransaction_becauseTheRowLocksWouldProtectNothing() throws Exception {
        Method method = OutboxCleanupStore.class.getMethod("deletePublishedBatch", long.class, int.class);

        assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
    }

    @Test
    void theStoreRefusesARetentionUnderADayAndAnOutOfRangeBatch_whateverTheCallerPasses() {
        OutboxCleanupStore store = new OutboxCleanupStore(null);

        assertThatThrownBy(() -> store.deletePublishedBatch(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deletePublishedBatch(86_399, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deletePublishedBatch(-1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deletePublishedBatch(86_400, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deletePublishedBatch(86_400, 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the tempting broad deletes: each one is unsafe, and the check says why

    @Test
    void aBroadStatusIndependentDeleteByAgeIsUnsafe() {
        String broad = "delete from outbox_events where created_at < now() - interval '30 days'";

        assertThat(violations(broad)).contains("the DELETE itself does not require status = 'published'",
                "candidates are not row-locked with SKIP LOCKED", "not bounded by a batch limit");
    }

    @Test
    void aDeleteThatIgnoresStatusButUsesPublishedAtIsUnsafe_itWouldTakeNullAndFailedRowsToo() {
        String noStatus = "with picked as materialized (select outbox_event_id from outbox_events where " + CUTOFF
                + " order by published_at asc, outbox_event_id asc limit ? for update skip locked)"
                + " delete from outbox_events e using picked p where e.outbox_event_id = p.outbox_event_id and e." + CUTOFF;

        assertThat(violations(noStatus)).contains("the DELETE itself does not require status = 'published'");
    }

    @Test
    void aDeleteOfPublishedAndFailedRowsIsUnsafe_failedEventsAreEvidenceAndUnrecoveredWork() {
        String both = OutboxCleanupStore.DELETE_PUBLISHED_BATCH.replace("status = 'published'", "status in ('published', 'failed')");

        assertThat(violations(both)).isNotEmpty();
    }

    @Test
    void aDeleteThatRestsOnTheSelectAloneIsUnsafe_theDeleteMustCheckEligibilityItself() {
        String trusting = "with picked as materialized (select outbox_event_id from outbox_events where status = 'published' and "
                + CUTOFF + " order by published_at asc, outbox_event_id asc limit ? for update skip locked)"
                + " delete from outbox_events e using picked p where e.outbox_event_id = p.outbox_event_id";

        assertThat(violations(trusting)).contains("the DELETE itself does not require status = 'published'",
                "the DELETE itself does not require published_at older than the database-derived cutoff");
    }

    @Test
    void aDeleteWithoutALockingSelectOrALimitIsUnsafe_itcouldrunForeverAndFightTheRelay() {
        String unbounded = "delete from outbox_events where status = 'published' and " + CUTOFF;

        assertThat(violations(unbounded)).contains("candidates are not row-locked with SKIP LOCKED", "not bounded by a batch limit");
    }

    @Test
    void aNonStrictCutoffOrACreatedAtCutoffIsUnsafe() {
        String nonStrict = OutboxCleanupStore.DELETE_PUBLISHED_BATCH.replace("published_at < now()", "published_at <= now()");
        String byCreation = OutboxCleanupStore.DELETE_PUBLISHED_BATCH.replace("published_at < now()", "created_at < now()");

        assertThat(violations(nonStrict)).isNotEmpty();
        assertThat(violations(byCreation)).isNotEmpty();
    }

    @Test
    void theRelaysOwnSelectionAndTheCleanupCanNeverTargetTheSameRows() {
        String cleanup = normalised(OutboxCleanupStore.DELETE_PUBLISHED_BATCH);

        assertThat(cleanup).contains("status = 'published'").doesNotContain("status = 'pending'");
    }
}
