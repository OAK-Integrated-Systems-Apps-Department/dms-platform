package com.oak.dms.notification.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NOT-01: pins the shape of the SQL the dispatcher depends on, without a database. These are text checks - they catch an
 * accidental edit of a predicate or lock clause; the behaviour of the statements themselves is exercised against PostgreSQL
 * by {@code NotificationLeaseRecoveryIT}.
 */
class DeliveryClaimRepositorySqlTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DeliveryClaimRepository repository = new DeliveryClaimRepository(jdbc);

    private static String normalise(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private String capturedQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return normalise(sql.getValue());
    }

    @BeforeEach
    void setUp() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(List.of());
    }

    @Test
    void theNormalClaimStillTakesOnlyDuePendingRowsWithoutALiveLease() {
        repository.claimLeases(120, 50);

        String sql = capturedQuery();
        assertThat(sql).contains("WHERE status = 'pending' AND next_attempt_at <= now()");
        assertThat(sql).contains("(locked_until IS NULL OR locked_until < now())");
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).contains("SET status = 'sending', locked_until = now() + make_interval(secs => ?)");
        // the lease it handed out comes back with the id: it is the fencing token for the result write
        assertThat(sql).contains("RETURNING delivery_id, locked_until");
    }

    @Test
    void theNormalClaimDoesNotTakeSendingRows() {
        repository.claimLeases(120, 50);

        // 'sending' appears only as the status being SET - never in the sub-select that chooses the rows
        String sql = capturedQuery();
        assertThat(sql.substring(sql.indexOf("WHERE delivery_id IN"))).doesNotContain("'sending'");
    }

    @Test
    void recoveryTakesOnlySendingRowsWhoseLeaseHasRunOutAndSkipsLockedRows() {
        repository.lockExpiredLeases(25);

        String sql = capturedQuery();
        assertThat(sql).contains("WHERE status = 'sending' AND (locked_until IS NULL OR locked_until < now())");
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).contains("LIMIT ?");
        assertThat(sql).doesNotContain("status = 'pending'");
    }

    @Test
    void recoveryIsBoundedByTheBatchSize() {
        repository.lockExpiredLeases(25);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), args.capture());
        assertThat(args.getValue()).containsExactly(25);
    }

    @Test
    void theFencingLockRequiresTheRowToBeSendingUnderExactlyTheGivenLease() {
        UUID id = UUID.randomUUID();
        OffsetDateTime lease = OffsetDateTime.now();

        boolean held = repository.lockIfStillLeased(id, lease);

        assertThat(held).isFalse(); // no row came back
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), eq(Integer.class), args.capture());
        String text = normalise(sql.getValue());
        assertThat(text).contains("WHERE delivery_id = ? AND status = 'sending' AND locked_until = ?");
        assertThat(text).endsWith("FOR UPDATE");
        assertThat(args.getValue()).containsExactly(id, lease);
    }

    @Test
    void theFencingLockReportsTrueWhenTheRowIsStillLeased() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(List.of(1));

        assertThat(repository.lockIfStillLeased(UUID.randomUUID(), OffsetDateTime.now())).isTrue();
    }
}
