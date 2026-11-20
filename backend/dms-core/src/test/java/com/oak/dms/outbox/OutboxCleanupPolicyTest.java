package com.oak.dms.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OBX-04: the cleanup settings - defaults, binding from configuration, and what is refused at startup. */
class OutboxCleanupPolicyTest {

    private static AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(OutboxCleanupPolicy.class);
        context.refresh();
        return context;
    }

    @Test
    void theCleanupIsOffByDefault_andTheDevelopmentDefaultsApply() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
            OutboxCleanupPolicy p = context.getBean(OutboxCleanupPolicy.class);

            assertThat(p.enabled()).as("disabled unless explicitly enabled").isFalse();
            assertThat(p.publishedRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(p.publishedRetentionSeconds()).isEqualTo(30L * 24 * 3600);
            assertThat(p.batchSize()).isEqualTo(100);
            assertThat(p.intervalMs()).isEqualTo(3_600_000L);
            assertThat(p.maxBatchesPerRun()).isEqualTo(1);
            assertThat(p.maxRowsPerRun()).as("100 rows an hour at most with the defaults").isEqualTo(100);
        }
    }

    @Test
    void everySettingIsConfigurableThroughItsProperty() {
        Map<String, Object> properties = Map.of(
                "dms.outbox.cleanup.enabled", "true",
                "dms.outbox.cleanup.published-retention-hours", "48",
                "dms.outbox.cleanup.batch-size", "500",
                "dms.outbox.cleanup.interval-ms", "60000",
                "dms.outbox.cleanup.max-batches-per-run", "5");
        try (AnnotationConfigApplicationContext context = contextWith(properties)) {
            OutboxCleanupPolicy p = context.getBean(OutboxCleanupPolicy.class);

            assertThat(p.enabled()).isTrue();
            assertThat(p.publishedRetention()).isEqualTo(Duration.ofHours(48));
            assertThat(p.batchSize()).isEqualTo(500);
            assertThat(p.intervalMs()).isEqualTo(60_000L);
            assertThat(p.maxBatchesPerRun()).isEqualTo(5);
            assertThat(p.maxRowsPerRun()).isEqualTo(2_500);
        }
    }

    @Test
    void theRetentionBoundary_twentyFourHoursIsTheMinimum() {
        assertThat(new OutboxCleanupPolicy(true, 24, 100, 3_600_000L, 1).publishedRetention()).isEqualTo(Duration.ofHours(24));
        assertThatThrownBy(() -> new OutboxCleanupPolicy(true, 23, 100, 3_600_000L, 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("published-retention-hours");
        assertThatThrownBy(() -> new OutboxCleanupPolicy(true, 0, 100, 3_600_000L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(true, -720, 100, 3_600_000L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(true, OutboxCleanupPolicy.MAX_RETENTION_HOURS + 1, 100, 3_600_000L, 1))
                .as("an absurdly large retention cannot overflow a duration").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBatchSizeMustBePositiveAndBounded() {
        assertThat(new OutboxCleanupPolicy(false, 720, 1, 3_600_000L, 1).batchSize()).isEqualTo(1);
        assertThat(new OutboxCleanupPolicy(false, 720, OutboxCleanupPolicy.MAX_BATCH_SIZE, 3_600_000L, 1).batchSize()).isEqualTo(10_000);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, 0, 3_600_000L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, -5, 3_600_000L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, OutboxCleanupPolicy.MAX_BATCH_SIZE + 1, 3_600_000L, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIntervalMustBePositiveAndNotAHotLoop() {
        assertThat(new OutboxCleanupPolicy(false, 720, 100, OutboxCleanupPolicy.MIN_INTERVAL_MS, 1).intervalMs()).isEqualTo(1_000L);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, 100, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, 100, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, 100, 999, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNumberOfBatchesPerRunMustBePositiveAndBounded() {
        assertThat(new OutboxCleanupPolicy(false, 720, 100, 3_600_000L, 100).maxBatchesPerRun()).isEqualTo(100);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, 100, 3_600_000L, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxCleanupPolicy(false, 720, 100, 3_600_000L, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConfigurationStopsTheApplicationFromStarting_evenWhileTheCleanupIsDisabled() {
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("dms.outbox.cleanup.published-retention-hours", "12"),
                Map.<String, Object>of("dms.outbox.cleanup.batch-size", "0"),
                Map.<String, Object>of("dms.outbox.cleanup.batch-size", "100000"),
                Map.<String, Object>of("dms.outbox.cleanup.interval-ms", "0"),
                Map.<String, Object>of("dms.outbox.cleanup.max-batches-per-run", "0"),
                Map.<String, Object>of("dms.outbox.cleanup.max-batches-per-run", "1000"))) {
            assertThatThrownBy(() -> contextWith(bad).close()).as(bad.toString()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> contextWith(Map.of("dms.outbox.cleanup.batch-size", "lots")).close())
                .as("not a number").isInstanceOf(RuntimeException.class);
    }

    @Test
    void theMostARunCanDeleteIsTheProductOfTheLimits() {
        assertThat(new OutboxCleanupPolicy(true, 720, 10_000, 3_600_000L, 100).maxRowsPerRun()).isEqualTo(1_000_000L);
    }
}
