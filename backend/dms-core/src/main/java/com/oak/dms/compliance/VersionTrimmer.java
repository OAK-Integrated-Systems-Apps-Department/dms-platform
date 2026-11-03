package com.oak.dms.compliance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Which old versions of one document a retention rule's version limits remove - pure rules, no database, so every
 * combination can be tested directly.
 *
 * <p>A rule can set a maximum number of versions to keep, a maximum age for superseded versions, both or neither. A version
 * is removed when it is outside EITHER limit, unless it is untouchable: the current, latest or published version, a protected
 * one, a signed one or one in an approval, one linked from a relationship, one an administrator chose to keep, a version still
 * being scanned. Untouchable versions are counted when working out which are "beyond the newest N" but are never removed,
 * so a document can keep more than N when some of its versions are in use - and always keeps at least its current one.
 */
public final class VersionTrimmer {

    private VersionTrimmer() {
    }

    /** One live version. {@code untouchable} is decided by the caller from the database (see VersionRetentionService). */
    public record Candidate(UUID versionId, int versionNumber, OffsetDateTime createdAt, boolean untouchable) {
    }

    /**
     * @param newestFirst   the document's live versions, newest first
     * @param maxVersions   keep at most this many (null = no limit)
     * @param maxAge        remove versions older than this (null = no limit)
     * @return the versions to remove, newest first
     */
    public static List<UUID> toRemove(List<Candidate> newestFirst, Integer maxVersions, RetentionSpan maxAge, OffsetDateTime now) {
        if (maxVersions == null && maxAge == null) {
            return List.of();
        }
        OffsetDateTime cutoff = maxAge == null ? null : maxAge.subtractFrom(now);
        List<UUID> remove = new ArrayList<>();
        for (int position = 0; position < newestFirst.size(); position++) {
            Candidate version = newestFirst.get(position);
            if (version.untouchable()) {
                continue;
            }
            boolean beyondCount = maxVersions != null && position >= maxVersions;
            boolean tooOld = cutoff != null && version.createdAt().isBefore(cutoff);
            if (beyondCount || tooOld) {
                remove.add(version.versionId());
            }
        }
        return remove;
    }
}
