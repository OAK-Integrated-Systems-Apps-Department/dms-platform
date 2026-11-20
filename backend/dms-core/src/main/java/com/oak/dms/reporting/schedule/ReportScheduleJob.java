package com.oak.dms.reporting.schedule;

import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.repository.ReportScheduleRepository;
import com.oak.dms.reporting.run.ReportRunWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * Wakes up every {@code dms.reports.schedule-poll} and starts what is due. Modelled on {@code RetentionJob}: one server at a time
 * (a Postgres session lock), switched off with {@code dms.reports.schedule-enabled}.
 * <p>
 * Each due schedule is taken in a transaction of its own with {@code FOR UPDATE SKIP LOCKED}: its row stays locked until its runs are
 * written and {@code next_run_at} has moved on, so even a second server that ignored the session lock could not take it too - and the
 * unique slot index on {@code report_runs} would stop a duplicate run if everything else failed. Workers are started only after that
 * transaction commits.
 * <p>
 * A passing identity-provider outage ends the poll quietly: nothing was changed, the schedules are still due, and the next poll
 * tries again - a schedule is never skipped because of it.
 */
@Slf4j
@Component
public class ReportScheduleJob {

    static final long LOCK_KEY = 0x646d_735f_7265_7073L;
    private static final int MAX_PER_POLL = 200;
    private static final int OWNER_SWEEP_BATCH = 50;

    private final JdbcTemplate jdbc;
    private final ReportScheduleRepository schedules;
    private final ReportScheduleDispatcher dispatcher;
    private final ReportPeople people;
    private final ReportRunWorker worker;
    private final ReportProperties properties;
    private final TransactionTemplate transaction;

    public ReportScheduleJob(JdbcTemplate jdbc, ReportScheduleRepository schedules, ReportScheduleDispatcher dispatcher,
                             ReportPeople people, ReportRunWorker worker, ReportProperties properties,
                             PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.dispatcher = dispatcher;
        this.people = people;
        this.worker = worker;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${dms.reports.schedule-poll:PT1M}", initialDelayString = "${dms.reports.initial-delay:PT20S}")
    public void scheduled() {
        if (properties.isEnabled() && properties.isScheduleEnabled()) {
            runOnce();
        }
    }

    /** One poll; returns how many schedules it handled, or -1 when another server is already polling. */
    public int runOnce() {
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return -1;
            }
            try {
                pauseSchedulesOfInactiveOwners();
                return startDue();
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
        });
    }

    private int startDue() {
        int handled = 0;
        for (int i = 0; i < MAX_PER_POLL; i++) {
            UUID[] current = new UUID[1];
            List<UUID> started;
            try {
                started = transaction.execute(status -> takeOne(current));
            } catch (IllegalStateException e) {
                log.warn("Scheduled reports waiting: could not check someone's access with the identity provider - will try again next poll");
                break;
            } catch (RuntimeException e) {
                log.error("A report schedule failed unexpectedly: {}", current[0], e);
                if (current[0] == null) {
                    break;
                }
                try {
                    transaction.executeWithoutResult(status -> dispatcher.quarantine(current[0]));
                } catch (RuntimeException again) {
                    log.error("Could not move report schedule {} past its failed slot", current[0], again);
                    break;
                }
                handled++;
                continue;
            }
            if (started == null) {
                break; // nothing more is due
            }
            handled++;
            started.forEach(worker::start);
        }
        return handled;
    }

    /** One due schedule, locked until this transaction ends. Null when nothing is due. */
    private List<UUID> takeOne(UUID[] current) {
        var due = schedules.lockDue(1);
        if (due.isEmpty()) {
            return null;
        }
        var schedule = due.get(0);
        current[0] = schedule.getScheduleId();
        return dispatcher.fire(schedule, schedule.getNextRunAt(), true);
    }

    /** An owner who left does not wait for their next slot to have their schedules paused. */
    private void pauseSchedulesOfInactiveOwners() {
        try {
            for (UUID id : people.activeSchedulesOfInactiveOwners(OWNER_SWEEP_BATCH)) {
                transaction.executeWithoutResult(status -> dispatcher.pauseForInactiveOwner(id));
            }
        } catch (RuntimeException e) {
            log.error("Could not pause the report schedules of users who left", e);
        }
    }

    private static boolean lock(java.sql.Connection connection, String sql) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
