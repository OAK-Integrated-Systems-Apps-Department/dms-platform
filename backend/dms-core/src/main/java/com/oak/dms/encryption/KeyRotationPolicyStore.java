package com.oak.dms.encryption;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Each tenant's master-key rotation schedule, in encryption_key_policies
 * (V59). A tenant without a row follows {@code dms.encryption.rotation.*}.
 * The key counts as created when the tenant's first object key was stored,
 * until it is first rotated.
 */
@Component
@RequiredArgsConstructor
public class KeyRotationPolicyStore {

    private final JdbcTemplate jdbc;
    private final EncryptionProperties properties;

    /**
     * @param intervalDays null when there is no schedule
     * @param custom       false while the tenant follows the defaults
     * @param keySince     the last rotation, or when the key was first used; null with no keys yet
     * @param nextDueAt    null with no schedule or no keys yet
     */
    public record RotationPolicy(Integer intervalDays, boolean autoRotate, boolean custom, OffsetDateTime keySince,
                                 OffsetDateTime lastRotatedAt, OffsetDateTime nextDueAt, boolean due,
                                 OffsetDateTime lastRemindedAt) {
    }

    public RotationPolicy get(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from encryption_key_policies where tenant_id = ?", tenantId);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        int defaults = properties.getRotation().getDefaultIntervalDays();
        Integer interval = rows.isEmpty() ? (defaults > 0 ? defaults : null)
                : (Integer) row.get("rotation_interval_days");
        boolean autoRotate = !rows.isEmpty() && Boolean.TRUE.equals(row.get("auto_rotate"));
        OffsetDateTime lastRotated = time(row.get("last_rotated_at"));
        OffsetDateTime since = lastRotated != null ? lastRotated : time(jdbc.queryForObject(
                "select min(created_at) from storage_object_keys where tenant_id = ?", Object.class, tenantId));
        OffsetDateTime next = interval == null || since == null ? null : since.plusDays(interval);
        return new RotationPolicy(interval, autoRotate, !rows.isEmpty(), since, lastRotated, next,
                next != null && !next.isAfter(OffsetDateTime.now()), time(row.get("last_reminded_at")));
    }

    /** @param intervalDays null turns the schedule off */
    public RotationPolicy save(UUID tenantId, Integer intervalDays, boolean autoRotate, UUID updatedBy) {
        jdbc.update("""
                insert into encryption_key_policies (tenant_id, rotation_interval_days, auto_rotate, updated_at, updated_by)
                values (?, ?, ?, now(), ?)
                on conflict (tenant_id) do update set rotation_interval_days = excluded.rotation_interval_days,
                    auto_rotate = excluded.auto_rotate, updated_at = now(), updated_by = excluded.updated_by
                """, tenantId, intervalDays, autoRotate, updatedBy);
        return get(tenantId);
    }

    /** Every rotation, by a person or the schedule: restarts the clock and clears the reminder. */
    public void recordRotation(UUID tenantId) {
        jdbc.update("""
                insert into encryption_key_policies (tenant_id, rotation_interval_days, auto_rotate, last_rotated_at)
                values (?, ?, false, now())
                on conflict (tenant_id) do update set last_rotated_at = now(), last_reminded_at = null
                """, tenantId, defaultInterval());
    }

    /** @return true when this caller should send the reminder now (once per {@code remind-every}) */
    public boolean claimReminder(UUID tenantId, Duration remindEvery) {
        // A tenant on the defaults gets a row here, carrying the defaults it had.
        return jdbc.update("""
                insert into encryption_key_policies (tenant_id, rotation_interval_days, auto_rotate, last_reminded_at)
                values (?, ?, false, now())
                on conflict (tenant_id) do update set last_reminded_at = now()
                    where encryption_key_policies.last_reminded_at is null
                       or encryption_key_policies.last_reminded_at < now() - make_interval(secs => ?)
                """, tenantId, defaultInterval(), remindEvery.toSeconds()) > 0;
    }

    /** Active tenants that hold encrypted objects and whose keys weren't shredded - the ones to check. */
    public List<UUID> tenantsToCheck() {
        return jdbc.queryForList("""
                select t.tenant_id from tenants t
                where not t.is_deleted and t.status = 'active' and t.keys_shredded_at is null
                  and exists (select 1 from storage_object_keys k where k.tenant_id = t.tenant_id)
                """, UUID.class);
    }

    private Integer defaultInterval() {
        int days = properties.getRotation().getDefaultIntervalDays();
        return days > 0 ? days : null;
    }

    private static OffsetDateTime time(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }
}
