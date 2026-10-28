package com.oak.dms.ocrworker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OcrJobTokenHealthIndicator}: OCR on with no secret
 * must be loud (DOWN, gauge = 1); OCR off stays quiet regardless of the
 * secret, and OCR on with a secret is healthy.
 */
class OcrJobTokenHealthIndicatorTest {

    private OcrProperties properties(boolean enabled) {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private OcrJobTokenCodec codec(String secret) {
        return new OcrJobTokenCodec(secret);
    }

    @Test
    void ocrEnabledWithNoSecret_isDownAndGaugeIsOne() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OcrJobTokenHealthIndicator indicator =
                new OcrJobTokenHealthIndicator(properties(true), codec(""), registry);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsKey("reason");
        assertThat(registry.get("dms.ocr.job_token.misconfigured").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void ocrEnabledWithSecretConfigured_isUpAndGaugeIsZero() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OcrJobTokenHealthIndicator indicator =
                new OcrJobTokenHealthIndicator(properties(true), codec("a-real-secret"), registry);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("dms.ocr.job_token.misconfigured").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void ocrDisabled_staysUpEvenWithoutASecret() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OcrJobTokenHealthIndicator indicator =
                new OcrJobTokenHealthIndicator(properties(false), codec(""), registry);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("dms.ocr.job_token.misconfigured").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void startupWarning_doesNotThrow_whenMisconfigured() {
        OcrJobTokenHealthIndicator indicator =
                new OcrJobTokenHealthIndicator(properties(true), codec(""), new SimpleMeterRegistry());
        indicator.warnAtStartupIfMisconfigured();
    }
}
