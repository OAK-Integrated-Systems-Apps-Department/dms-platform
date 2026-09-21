package com.oak.dms.audit.helper;

import com.oak.dms.audit.entity.AuditEvent;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Computes audit_events.record_hash: a SHA-256 digest of a row's own
 * content, using one fixed, documented field order and encoding - so the
 * same data always re-hashes to the same value, and the hash chain can
 * never appear "broken" merely because two code paths serialized a row
 * differently (see the record_hash note in
 * docs/audit_events_schema_spec.md).
 *
 * Each field is written as a 4-byte big-endian length prefix followed by
 * its UTF-8 bytes (absent = length -1, no bytes), rather than joined with
 * a delimiter like "|". A delimiter could appear inside a free-text field
 * (reason, user_agent, the JSON blobs) and make two genuinely different
 * records hash identically if their content merely shifted across the
 * delimiter boundary; length-prefixing makes that impossible.
 *
 * previous_hash is included as the final field, which is what actually
 * chains each row to the one before it - a tampered row can no longer be
 * edited in place without also changing the record_hash every later row
 * was computed from.
 */
@Component
public class AuditRecordHasher {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public String hash(AuditEvent event) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeField(buffer, event.getId());
        writeField(buffer, event.getTenantId());
        writeField(buffer, event.getActorId());
        writeField(buffer, event.getActorType());
        writeField(buffer, event.getActorDisplayName());
        writeField(buffer, event.getImpersonatedBy());
        writeField(buffer, event.getAction());
        writeField(buffer, event.getModule());
        writeField(buffer, event.getObjectType());
        writeField(buffer, event.getObjectId());
        writeField(buffer, event.getResourceName());
        writeField(buffer, event.getObjectVersion());
        writeField(buffer, event.getOutcome());
        writeField(buffer, event.getFailureReason());
        writeField(buffer, event.getOccurredAt());
        writeField(buffer, event.getPreviousValue());
        writeField(buffer, event.getNewValue());
        writeField(buffer, event.getReason());
        writeField(buffer, event.getSessionId());
        writeField(buffer, event.getAuthMethod());
        writeField(buffer, event.getPermissionCode());
        writeField(buffer, event.getDataClassification());
        writeField(buffer, event.getLegalHoldFlag());
        writeField(buffer, event.getIpAddress());
        writeField(buffer, event.getUserAgent());
        writeField(buffer, event.getClientType());
        writeField(buffer, event.getCorrelationId());
        writeField(buffer, event.getRequestId());
        writeField(buffer, event.getSeverity());
        writeField(buffer, event.getPreviousHash());

        return HexFormat.of().formatHex(sha256(buffer.toByteArray()));
    }

    private void writeField(ByteArrayOutputStream buffer, Object value) {
        String encoded;
        if (value == null) {
            encoded = null;
        } else if (value instanceof OffsetDateTime timestamp) {
            encoded = TIMESTAMP_FORMAT.format(timestamp);
        } else {
            encoded = value.toString();
        }
        byte[] bytes = encoded == null ? null : encoded.getBytes(StandardCharsets.UTF_8);
        int length = bytes == null ? -1 : bytes.length;
        buffer.write((length >>> 24) & 0xFF);
        buffer.write((length >>> 16) & 0xFF);
        buffer.write((length >>> 8) & 0xFF);
        buffer.write(length & 0xFF);
        if (bytes != null) {
            buffer.writeBytes(bytes);
        }
    }

    private byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
