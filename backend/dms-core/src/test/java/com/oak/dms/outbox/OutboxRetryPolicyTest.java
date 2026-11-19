package com.oak.dms.outbox;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OBX-03: the retry schedule and the retry window, with the randomness injected so every assertion is deterministic. */
class OutboxRetryPolicyTest {

    private static final DoubleSupplier MIDDLE = () -> 0.5;                 // factor exactly 1.0: no jitter

    private static OutboxRetryPolicy policy(DoubleSupplier random) {
        return new OutboxRetryPolicy(10_000, 15 * 60_000, 0.2, 24 * 3_600_000L, 150, random);
    }

    @Test
    void theFirstRetryWaitsTheInitialDelay() {
        assertThat(policy(MIDDLE).delayAfter(1)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theDelayDoublesWithEveryFailedAttempt() {
        OutboxRetryPolicy p = policy(MIDDLE);

        assertThat(p.delayAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(p.delayAfter(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(p.delayAfter(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(p.delayAfter(7)).isEqualTo(Duration.ofSeconds(640));
    }

    @Test
    void theDelayIsCappedAtTheMaximum() {
        OutboxRetryPolicy p = policy(MIDDLE);

        assertThat(p.delayAfter(8)).as("1,280 s would be more than 15 minutes").isEqualTo(Duration.ofMinutes(15));
        assertThat(p.delayAfter(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void hugeAttemptCountsNeitherOverflowNorGoBelowTheInitialDelay() {
        OutboxRetryPolicy p = policy(MIDDLE);

        assertThat(p.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.delayAfter(0)).as("a nonsensical zero is treated as the first failure").isEqualTo(Duration.ofSeconds(10));
        assertThat(p.delayAfter(-7)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterSpreadsTheDelayByAtMostTwentyPercentEitherWay() {
        assertThat(policy(() -> 0.0).delayAfter(1)).as("lowest").isEqualTo(Duration.ofMillis(8_000));
        assertThat(policy(() -> 1.0).delayAfter(1)).as("highest (the supplier's range is [0, 1))").isBetween(Duration.ofMillis(11_990), Duration.ofMillis(12_000));
        assertThat(policy(() -> 0.0).delayAfter(8)).as("jitter applies around the cap").isEqualTo(Duration.ofSeconds(720));
        assertThat(policy(() -> 1.0).delayAfter(8)).isBetween(Duration.ofMillis(1_079_990), Duration.ofMillis(1_080_000));
    }

    @Test
    void everyJitteredDelayStaysInsideItsBounds() {
        Random seeded = new Random(42);
        OutboxRetryPolicy p = policy(seeded::nextDouble);

        for (int attempt = 1; attempt <= 60; attempt++) {
            double base = Math.min(15 * 60_000.0, 10_000.0 * Math.pow(2, Math.min(attempt - 1, 40)));
            for (int i = 0; i < 50; i++) {
                long delay = p.delayAfter(attempt).toMillis();
                assertThat(delay).as("attempt %d", attempt).isBetween(Math.round(base * 0.8), Math.round(base * 1.2));
            }
        }
    }

    @Test
    void sameRandomnessGivesTheSameDelays() {
        OutboxRetryPolicy first = policy(new Random(7)::nextDouble);
        OutboxRetryPolicy second = policy(new Random(7)::nextDouble);

        for (int attempt = 1; attempt <= 12; attempt++) {
            assertThat(first.delayAfter(attempt)).isEqualTo(second.delayAfter(attempt));
        }
    }

    @Test
    void aDelayIsAlwaysPositive_soARetryIsNeverScheduledInThePast() {
        OutboxRetryPolicy extreme = new OutboxRetryPolicy(1, 1, 0.99, 1_000, 5, () -> 0.0);

        assertThat(extreme.delayAfter(1).toMillis()).isGreaterThanOrEqualTo(1);
        assertThat(policy(() -> -5.0).delayAfter(1)).as("an out-of-range random value is clamped").isEqualTo(Duration.ofMillis(8_000));
        assertThat(policy(() -> 9.0).delayAfter(1).toMillis()).isLessThanOrEqualTo(12_000);
    }

    @Test
    void anEventIsExhaustedOnlyWhenItIsOlderThanTheRetryWindow() {
        OutboxRetryPolicy p = policy(MIDDLE);

        assertThat(p.isExhausted(1, Duration.ofHours(23).plusMinutes(59))).isFalse();
        assertThat(p.isExhausted(1, Duration.ofHours(24))).isTrue();
        assertThat(p.isExhausted(40, Duration.ofSeconds(30))).as("many attempts alone are not the policy").isFalse();
    }

    @Test
    void theAttemptGuardStopsARunawayEvenInsideTheWindow() {
        OutboxRetryPolicy p = new OutboxRetryPolicy(10_000, 900_000, 0.2, 86_400_000L, 5, MIDDLE);

        assertThat(p.isExhausted(4, Duration.ofSeconds(1))).isFalse();
        assertThat(p.isExhausted(5, Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void aNegativeAgeFromClockSkewIsNotExhausted() {
        assertThat(policy(MIDDLE).isExhausted(1, Duration.ofSeconds(-30))).isFalse();
    }

    @Test
    void nonsensicalConfigurationIsRefusedSoThereIsNeverAnUnboundedOrInvertedSchedule() {
        assertThatThrownBy(() -> new OutboxRetryPolicy(0, 1, 0.1, 1, 1, MIDDLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 5, 0.1, 1, 1, MIDDLE)).as("cap below the initial delay").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, 1.0, 1, 1, MIDDLE)).as("jitter of 100% could reach zero").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, -0.1, 1, 1, MIDDLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, 0.1, 0, 1, MIDDLE)).as("no retry window = unbounded").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, 0.1, 1, 0, MIDDLE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMultiplierIsConfigurable_andTheScheduleFollowsIt() {
        OutboxRetryPolicy triple = new OutboxRetryPolicy(10_000, 15 * 60_000, 0.2, 86_400_000L, 150, 3.0, MIDDLE);
        OutboxRetryPolicy constant = new OutboxRetryPolicy(10_000, 15 * 60_000, 0.2, 86_400_000L, 150, 1.0, MIDDLE);

        assertThat(triple.delayAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(triple.delayAfter(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(triple.delayAfter(3)).isEqualTo(Duration.ofSeconds(90));
        assertThat(triple.delayAfter(50)).as("still capped").isEqualTo(Duration.ofMinutes(15));
        assertThat(constant.delayAfter(30)).as("a multiplier of 1 is a constant delay").isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aMultiplierOutsideOneToTenIsRefused_soThePowerNeverOverflows() {
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, 0.1, 1, 1, 0.5, MIDDLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, 0.1, 1, 1, 10.5, MIDDLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(10, 10, 0.1, 1, 1, Double.NaN, MIDDLE)).isInstanceOf(IllegalArgumentException.class);
        OutboxRetryPolicy biggest = new OutboxRetryPolicy(10_000, 15 * 60_000, 0.2, 86_400_000L, 150, 10.0, MIDDLE);
        assertThat(biggest.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theMaximumActualDelayIsTheCapPlusItsJitter_eighteenMinutesByDefault() {
        assertThat(OutboxRetryPolicy.defaults().maxActualDelay()).isEqualTo(Duration.ofMinutes(18));
        for (int attempt : new int[]{8, 9, 20, 150, Integer.MAX_VALUE}) {
            assertThat(policy(() -> 1.0).delayAfter(attempt)).isLessThanOrEqualTo(OutboxRetryPolicy.defaults().maxActualDelay());
        }
    }

    @Test
    void theRetryWindowBoundary_oneMillisecondBeforeIsNotExhausted_exactlyAtItIs() {
        OutboxRetryPolicy p = OutboxRetryPolicy.defaults();
        Duration window = Duration.ofHours(24);

        assertThat(p.isExhausted(1, window.minusMillis(1))).as("one millisecond inside the window: one more attempt is scheduled").isFalse();
        assertThat(p.isExhausted(1, window)).as("exactly at the boundary: exhausted").isTrue();
        assertThat(p.isExhausted(1, window.plusMillis(1))).isTrue();
    }

    @Test
    void theAttemptGuardBoundaryIsTheFailureThatReachesTheLimit() {
        OutboxRetryPolicy p = OutboxRetryPolicy.defaults();

        assertThat(p.isExhausted(149, Duration.ofHours(1))).isFalse();
        assertThat(p.isExhausted(150, Duration.ofHours(1))).as("the 150th failed attempt is the last").isTrue();
        assertThat(p.isExhausted(151, Duration.ofHours(1))).isTrue();
    }

    @Test
    void withTheApprovedDefaultsTheWindowCannotHoldTheAttemptGuard() {
        // the fastest possible schedule: every jitter draw at its minimum. How many attempts fit into 24 hours?
        OutboxRetryPolicy fastest = new OutboxRetryPolicy(10_000, 15 * 60_000, 0.2, 86_400_000L, 150, () -> 0.0);
        long elapsedMs = 0;
        int attempts = 0;
        while (elapsedMs < 86_400_000L) {
            attempts++;
            elapsedMs += fastest.delayAfter(attempts).toMillis();
        }

        assertThat(attempts).as("attempts that fit the window at the minimum spacing").isLessThan(150).isGreaterThan(100);
    }

    // ---- the settings really bind from configuration, and invalid ones stop startup (a real Spring context, no infrastructure)

    private static AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(OutboxRetryPolicy.class);
        context.refresh();
        return context;
    }

    @Test
    void withNoConfigurationTheApprovedDevelopmentDefaultsApply() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
            OutboxRetryPolicy p = context.getBean(OutboxRetryPolicy.class);

            assertThat(p.maxAgeMs()).isEqualTo(24 * 3_600_000L);
            assertThat(p.maxAttempts()).isEqualTo(150);
            assertThat(p.maxActualDelay()).isEqualTo(Duration.ofMinutes(18));
            assertThat(p.delayAfter(1).toMillis()).isBetween(8_000L, 12_000L);
            assertThat(p.delayAfter(2).toMillis()).isBetween(16_000L, 24_000L);
        }
    }

    @Test
    void everySettingIsConfigurableThroughItsProperty() {
        Map<String, Object> properties = Map.of(
                "dms.outbox.retry.initial-delay-ms", "5000",
                "dms.outbox.retry.max-delay-ms", "60000",
                "dms.outbox.retry.jitter", "0.1",
                "dms.outbox.retry.max-age-ms", "3600000",
                "dms.outbox.retry.max-attempts", "10",
                "dms.outbox.retry.multiplier", "3");
        try (AnnotationConfigApplicationContext context = contextWith(properties)) {
            OutboxRetryPolicy p = context.getBean(OutboxRetryPolicy.class);

            assertThat(p.maxAgeMs()).isEqualTo(3_600_000L);
            assertThat(p.maxAttempts()).isEqualTo(10);
            assertThat(p.maxActualDelay()).isEqualTo(Duration.ofMillis(66_000));
            assertThat(p.delayAfter(1).toMillis()).isBetween(4_500L, 5_500L);
            assertThat(p.delayAfter(2).toMillis()).as("5 s x 3").isBetween(13_500L, 16_500L);
            assertThat(p.isExhausted(10, Duration.ZERO)).isTrue();
        }
    }

    @Test
    void anInvalidSettingStopsTheApplicationFromStarting() {
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("dms.outbox.retry.multiplier", "0.5"),
                Map.<String, Object>of("dms.outbox.retry.jitter", "1.0"),
                Map.<String, Object>of("dms.outbox.retry.max-age-ms", "0"),
                Map.<String, Object>of("dms.outbox.retry.max-attempts", "0"),
                Map.<String, Object>of("dms.outbox.retry.initial-delay-ms", "-1"),
                Map.<String, Object>of("dms.outbox.retry.max-delay-ms", "1000"))) {                 // below the 10 s initial delay
            assertThatThrownBy(() -> contextWith(bad).close()).as(bad.toString())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> contextWith(Map.of("dms.outbox.retry.max-attempts", "many")).close())
                .as("a value that is not a number").isInstanceOf(RuntimeException.class);
    }

    @Test
    void theDefaultsAreTheProvisionalPolicy() {
        OutboxRetryPolicy p = OutboxRetryPolicy.defaults();
        assertThat(OutboxRetryPolicy.DEFAULT_MULTIPLIER).isEqualTo(2.0);

        assertThat(p.maxAgeMs()).isEqualTo(24 * 3_600_000L);
        assertThat(p.maxAttempts()).isEqualTo(150);
        assertThat(OutboxRetryPolicy.DEFAULT_INITIAL_DELAY_MS).isEqualTo(10_000);
        assertThat(OutboxRetryPolicy.DEFAULT_MAX_DELAY_MS).isEqualTo(900_000);
        assertThat(OutboxRetryPolicy.DEFAULT_JITTER).isEqualTo(0.2);
    }
}
