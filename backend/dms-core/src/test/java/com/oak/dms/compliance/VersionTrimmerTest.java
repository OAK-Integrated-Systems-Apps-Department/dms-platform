package com.oak.dms.compliance;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which old versions a rule's limits remove, and which are never removed whatever the limits say. */
class VersionTrimmerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneOffset.UTC);

    /** Versions numbered count..1, newest first; each {@code ageDays[i]} old; {@code untouchable} lists version numbers. */
    private static List<VersionTrimmer.Candidate> versions(int count, int[] ageDays, int... untouchable) {
        List<VersionTrimmer.Candidate> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int number = count - i;
            final int n = number;
            boolean locked = java.util.Arrays.stream(untouchable).anyMatch(u -> u == n);
            list.add(new VersionTrimmer.Candidate(UUID.nameUUIDFromBytes(("v" + number).getBytes()), number,
                    NOW.minusDays(ageDays[i]), locked));
        }
        return list;
    }

    private static UUID id(int number) {
        return UUID.nameUUIDFromBytes(("v" + number).getBytes());
    }

    @Test
    void noLimits_removesNothing() {
        assertThat(VersionTrimmer.toRemove(versions(5, new int[]{1, 2, 3, 4, 5}), null, null, NOW)).isEmpty();
    }

    @Test
    void aMaximumCount_keepsTheNewest_andRemovesTheRest() {
        var removed = VersionTrimmer.toRemove(versions(5, new int[]{1, 2, 3, 4, 5}), 2, null, NOW);
        assertThat(removed).containsExactly(id(3), id(2), id(1));
    }

    @Test
    void aMaximumAge_removesOnlyTheOldOnes() {
        var removed = VersionTrimmer.toRemove(versions(4, new int[]{1, 10, 400, 800}), null, new RetentionSpan(1, "years"), NOW);
        assertThat(removed).containsExactly(id(2), id(1)); // the two older than a year
    }

    @Test
    void withBothLimits_aVersionOutsideEitherIsRemoved() {
        // 5 versions: count keeps 4 and 5 (newest two); age (over 100 days) also catches version 3.
        var removed = VersionTrimmer.toRemove(versions(5, new int[]{1, 2, 150, 5, 6}), 4, new RetentionSpan(100, "days"), NOW);
        assertThat(removed).containsExactly(id(3), id(1)); // 3 is too old; 1 is beyond the newest four
    }

    @Test
    void anUntouchableVersion_isNeverRemoved_butStillCountsTowardsTheMaximum() {
        // Newest two are kept; version 3 is in use so it stays (and counts as the third); 2 and 1 go.
        var removed = VersionTrimmer.toRemove(versions(5, new int[]{1, 2, 3, 4, 5}, 3), 2, null, NOW);
        assertThat(removed).containsExactly(id(2), id(1));
        assertThat(removed).doesNotContain(id(3));
    }

    @Test
    void theCurrentVersionIsAlwaysKept_evenWhenEveryLimitWouldRemoveIt() {
        // The newest is untouchable (the current one); with a 1-day age limit everything else goes.
        var removed = VersionTrimmer.toRemove(versions(3, new int[]{50, 60, 70}, 3), 1, new RetentionSpan(1, "days"), NOW);
        assertThat(removed).containsExactly(id(2), id(1));
    }

    @Test
    void aSingleVersionDocument_isNeverTrimmedBelowOne() {
        assertThat(VersionTrimmer.toRemove(versions(1, new int[]{900}, 1), 1, new RetentionSpan(1, "days"), NOW)).isEmpty();
    }
}
