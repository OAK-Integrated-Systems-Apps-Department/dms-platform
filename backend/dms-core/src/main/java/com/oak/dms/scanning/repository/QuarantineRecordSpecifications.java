package com.oak.dms.scanning.repository;

import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.enums.QuarantineStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Filters for the quarantine list, combined with AND - mirrors
 * {@code AuditEventSpecifications}. {@link #tenant} is always applied by the
 * service; every other filter is optional (null = no filter).
 * <p>
 * Soft-deleted rows are deliberately NOT excluded here: a permanently
 * deleted quarantine record keeps its row (status {@code deleted}) as the
 * record of what was caught, and the status filter decides whether it shows.
 */
public final class QuarantineRecordSpecifications {

    private QuarantineRecordSpecifications() {
    }

    public static Specification<QuarantineRecord> tenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<QuarantineRecord> status(QuarantineStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<QuarantineRecord> detectedFrom(OffsetDateTime from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<QuarantineRecord> detectedTo(OffsetDateTime to) {
        return (root, query, cb) -> to == null ? null : cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    /** Case-insensitive substring match on the original filename or the threat name. */
    public static Specification<QuarantineRecord> search(String term) {
        return (root, query, cb) -> {
            if (term == null || term.isBlank()) {
                return null;
            }
            String pattern = "%" + term.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("originalFilename")), pattern, '\\'),
                    cb.like(cb.lower(root.get("threatName")), pattern, '\\'));
        };
    }
}
