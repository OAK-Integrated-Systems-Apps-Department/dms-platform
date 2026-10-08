package com.oak.dms.workflow.helper;

import com.oak.dms.workflow.enums.CompletionRule;
import com.oak.dms.workflow.enums.RejectionRule;
import com.oak.dms.workflow.helper.StageOutcomeEvaluator.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.oak.dms.workflow.helper.StageOutcomeEvaluator.Outcome.APPROVED;
import static com.oak.dms.workflow.helper.StageOutcomeEvaluator.Outcome.REJECTED;
import static com.oak.dms.workflow.helper.StageOutcomeEvaluator.Outcome.STILL_OPEN;
import static org.assertj.core.api.Assertions.assertThat;

/** The completion/rejection truth table (docs/workflow-phase1-plan.md W6). */
class StageOutcomeEvaluatorTest {

    @ParameterizedTest(name = "{0} n={1} -> {2}")
    @CsvSource({
            "all, 1, 1", "all, 2, 2", "all, 3, 3", "all, 4, 4",
            "any, 1, 1", "any, 4, 1", "any, 6, 1",
            // strict majority: a 2-2 split never approves
            "majority, 1, 1", "majority, 2, 2", "majority, 3, 2", "majority, 4, 3", "majority, 5, 3", "majority, 6, 4"})
    void requiredCount(CompletionRule rule, int n, int required) {
        assertThat(StageOutcomeEvaluator.requiredCount(rule, n)).isEqualTo(required);
    }

    @ParameterizedTest(name = "{0}/{1} n={2} req={3} a={4} r={5} -> {6}")
    @CsvSource({
            "all, halt_immediately, 3, 3, 1, 1, REJECTED",     // first rejection halts
            "all, wait_for_all,     3, 3, 1, 1, STILL_OPEN",   // must wait for the third entry
            "all, wait_for_all,     3, 3, 2, 1, REJECTED",     // all responded, 2 < 3
            "all, wait_for_all,     3, 3, 3, 0, APPROVED",
            "any, halt_immediately, 3, 1, 0, 1, REJECTED",
            "any, wait_for_all,     3, 1, 0, 2, STILL_OPEN",   // one entry still to respond
            "any, wait_for_all,     3, 1, 0, 3, REJECTED",     // all responded, 0 < 1
            "any, wait_for_all,     3, 1, 1, 1, APPROVED",     // approval short-circuits
            "majority, wait_for_all, 3, 2, 1, 1, STILL_OPEN",  // Scenario H: Legal approves, Finance rejects
            "majority, wait_for_all, 3, 2, 2, 1, APPROVED",    // H: Dept Head approves
            "majority, wait_for_all, 3, 2, 1, 2, REJECTED",    // H: Dept Head rejects
            "majority, wait_for_all, 4, 3, 0, 2, STILL_OPEN",  // approval already impossible, still waits
            "majority, wait_for_all, 4, 3, 2, 2, REJECTED",    // 2-2 split, all responded
            "majority, halt_immediately, 4, 3, 2, 1, REJECTED",
            "majority, wait_for_all, 4, 3, 3, 0, APPROVED"})   // short-circuits; the 4th task is cancelled
    void namedRows(CompletionRule completion, RejectionRule rejection, int n, int req, int a, int r, Outcome expected) {
        assertThat(StageOutcomeEvaluator.requiredCount(completion, n)).isEqualTo(req);
        assertThat(StageOutcomeEvaluator.evaluate(rejection, n, req, a, r)).isEqualTo(expected);
    }

    /** Every reachable (a, r) with a + r <= n, for n = 1..6, against the oracle. */
    @Test
    void fullTruthTable() {
        for (CompletionRule completion : CompletionRule.values()) {
            for (RejectionRule rejection : RejectionRule.values()) {
                for (int n = 1; n <= 6; n++) {
                    int req = StageOutcomeEvaluator.requiredCount(completion, n);
                    for (int a = 0; a <= n; a++) {
                        for (int r = 0; a + r <= n; r++) {
                            Outcome expected = a >= req ? APPROVED
                                    : rejection == RejectionRule.halt_immediately && r > 0 ? REJECTED
                                    : rejection == RejectionRule.wait_for_all && a + r == n ? REJECTED
                                    : STILL_OPEN;
                            assertThat(StageOutcomeEvaluator.evaluate(rejection, n, req, a, r))
                                    .as("%s/%s n=%d a=%d r=%d", completion, rejection, n, a, r)
                                    .isEqualTo(expected);
                        }
                    }
                }
            }
        }
    }

    /** wait_for_all never concludes early on rejections - even under 'all', where approval is already impossible. */
    @Test
    void waitForAllNeverRejectsBeforeEveryEntryResponded() {
        for (int n = 2; n <= 6; n++) {
            for (int r = 1; r < n; r++) {
                assertThat(StageOutcomeEvaluator.evaluate(RejectionRule.wait_for_all, n, n, 0, r)).isEqualTo(STILL_OPEN);
            }
        }
    }
}
