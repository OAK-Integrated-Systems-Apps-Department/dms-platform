package com.oak.dms.reporting.schedule;

import com.oak.dms.reporting.dto.ScheduleRequest.Recipient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** A schedule's recipient rows (V138 {@code report_schedule_recipients}). */
@Component
public class ReportRecipientStore {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRecipientStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Recipient> recipientsOf(UUID scheduleId) {
        return jdbc.query("""
                select recipient_type, recipient_ref from report_schedule_recipients
                where schedule_id = :id order by recipient_type, recipient_ref""",
                new MapSqlParameterSource("id", scheduleId), (rs, i) -> new Recipient(rs.getString(1), rs.getString(2)));
    }

    /** Replaces every recipient of the schedule with exactly these (already validated and de-duplicated). */
    public void replace(UUID tenantId, UUID scheduleId, List<Recipient> recipients) {
        jdbc.update("delete from report_schedule_recipients where schedule_id = :id", new MapSqlParameterSource("id", scheduleId));
        for (Recipient r : recipients) {
            jdbc.update("""
                    insert into report_schedule_recipients (schedule_id, tenant_id, recipient_type, recipient_ref)
                    values (:schedule, :tenant, :type, :ref)""",
                    new MapSqlParameterSource("schedule", scheduleId).addValue("tenant", tenantId)
                            .addValue("type", r.type()).addValue("ref", r.ref()));
        }
    }

    /**
     * What goes in the delivery log in place of a person: a hash, so a table that might be exported or audited never holds an address.
     * Lower-cased first, so the same address always hashes the same.
     */
    public static String hash(String type, String ref) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((type + ":" + ref.toLowerCase()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
