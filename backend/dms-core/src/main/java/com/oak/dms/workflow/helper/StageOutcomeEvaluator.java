package com.oak.dms.workflow.helper;

import com.oak.dms.workflow.enums.CompletionRule;
import com.oak.dms.workflow.enums.RejectionRule;

/**
 * The completion and rejection rules of a stage, as pure functions over the
 * per-ENTRY counters (docs/workflow-phase1-plan.md §4). Kept free of
 * persistence so the full truth table is unit-testable.
 */
public final class StageOutcomeEvaluator {

    public enum Outcome { APPROVED, REJECTED, STILL_OPEN }

    private StageOutcomeEvaluator() {
    }

    /** all = n, any = 1, majority = strict majority floor(n/2)+1 (a 2-2 split never approves). */
    public static int requiredCount(CompletionRule rule, int entryCount) {
        if (entryCount <= 0) {
            return 0;
        }
        return switch (rule) {
            case all -> entryCount;
            case any -> 1;
            case majority -> entryCount / 2 + 1;
        };
    }

    /**
     * Approval short-circuits under every rule. halt_immediately rejects on
     * the first rejection. wait_for_all keeps collecting until every entry
     * has responded - even once approval is out of reach - and only then
     * rejects (Scenario H).
     */
    public static Outcome evaluate(RejectionRule rejectionRule, int entryCount, int requiredCount,
                                   int approvedCount, int rejectedCount) {
        if (approvedCount >= requiredCount) {
            return Outcome.APPROVED;
        }
        if (rejectionRule == RejectionRule.halt_immediately && rejectedCount > 0) {
            return Outcome.REJECTED;
        }
        if (rejectionRule == RejectionRule.wait_for_all && approvedCount + rejectedCount >= entryCount) {
            return Outcome.REJECTED;
        }
        return Outcome.STILL_OPEN;
    }
}
