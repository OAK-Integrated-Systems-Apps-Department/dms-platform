package com.oak.dms.reporting.run;

import com.oak.dms.reporting.ReportException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.oak.dms.reporting.run.ReportRunStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every legal move works, and every other pair - all 41 of them - is refused, one assertion each. */
class ReportRunStateMachineTest {

    private final ReportRunStateMachine machine = new ReportRunStateMachine();

    private static final java.util.Map<ReportRunStatus, Set<ReportRunStatus>> LEGAL = java.util.Map.of(
            queued, EnumSet.of(running, cancelled, skipped, failed),
            running, EnumSet.of(completed, failed, cancelled),
            completed, EnumSet.of(expired),
            failed, EnumSet.noneOf(ReportRunStatus.class),
            cancelled, EnumSet.noneOf(ReportRunStatus.class),
            expired, EnumSet.noneOf(ReportRunStatus.class),
            skipped, EnumSet.noneOf(ReportRunStatus.class));

    @Test
    void everyLegalTransitionIsAccepted() {
        LEGAL.forEach((from, targets) -> targets.forEach(to ->
                assertThat(machine.transition(from, to)).as(from + " -> " + to).isEqualTo(to)));
    }

    @Test
    void everyIllegalTransitionIsRefusedWithAConflict() {
        int refused = 0;
        for (ReportRunStatus from : ReportRunStatus.values()) {
            for (ReportRunStatus to : ReportRunStatus.values()) {
                if (LEGAL.get(from).contains(to)) {
                    continue;
                }
                refused++;
                assertThatThrownBy(() -> machine.transition(from, to)).as(from + " -> " + to)
                        .isInstanceOfSatisfying(ReportException.class, e -> {
                            assertThat(e.getCode()).isEqualTo("REPORT_RUN_STATE_INVALID");
                            assertThat(e.getStatus().value()).isEqualTo(409);
                        });
            }
        }
        assertThat(refused).isEqualTo(49 - 8); // 7x7 pairs minus the 8 legal ones; a move to the same state is refused too
    }

    @Test
    void aFinishedRunNeverGoesBack() {
        for (ReportRunStatus final_ : EnumSet.of(failed, cancelled, expired, skipped)) {
            assertThat(final_.finished()).isTrue();
            for (ReportRunStatus to : ReportRunStatus.values()) {
                assertThat(machine.allows(final_, to)).as(final_ + " -> " + to).isFalse();
            }
        }
    }

    @Test
    void cancelIsAllowedOnlyWhileQueuedOrRunning() {
        assertThat(machine.canCancel(queued)).isTrue();
        assertThat(machine.canCancel(running)).isTrue();
        for (ReportRunStatus s : EnumSet.of(completed, failed, cancelled, expired, skipped)) {
            assertThat(machine.canCancel(s)).as(s.name()).isFalse();
        }
    }

    @Test
    void onlyQueuedAndRunningAreNotFinished() {
        assertThat(queued.finished()).isFalse();
        assertThat(running.finished()).isFalse();
        assertThat(completed.finished()).isTrue();
    }
}
