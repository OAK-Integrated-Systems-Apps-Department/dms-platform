package com.oak.dms.ocrworker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Makes "OCR is on but has no job-token secret" visible without anyone
 * uploading a document first.
 *
 * <h2>Why this exists</h2>
 * {@link OcrEnqueueService#enqueue} already declines (not throws) when
 * {@link OcrJobTokenCodec#isConfigured()} is false, logging an ERROR each
 * time - but only when an upload happens to run that code path. A
 * deployment with {@code dms.ocr.enabled=true} and a blank
 * {@code dms.ocr.job-token-secret} can sit for hours with nothing uploaded
 * and show nothing wrong: every version is silently held back for the
 * backlog sweeper, and the first signal anyone sees is "OCR never finishes."
 * This indicator, the matching gauge below, and the startup log line make
 * the same fact visible the moment the app comes up, not the moment someone
 * notices documents are stuck.
 *
 * <p>{@code dms.ocr.enabled=false} stays the quiet case on purpose - that is
 * an intentional deployment choice, not a misconfiguration, so it reports
 * UP here exactly as if OCR did not exist.
 */
@Slf4j
@Component("ocrJobToken")
public class OcrJobTokenHealthIndicator implements HealthIndicator {

    private final OcrProperties ocrProperties;
    private final OcrJobTokenCodec jobTokenCodec;

    public OcrJobTokenHealthIndicator(OcrProperties ocrProperties, OcrJobTokenCodec jobTokenCodec,
                                       MeterRegistry meterRegistry) {
        this.ocrProperties = ocrProperties;
        this.jobTokenCodec = jobTokenCodec;
        // 1 = misconfigured (OCR on, no secret), 0 = fine either way. A gauge
        // rather than a counter: this is a standing state, not an event count,
        // and it must go back to 0 the moment the secret is set without a
        // restart being needed to "clear" it.
        Gauge.builder("dms.ocr.job_token.misconfigured", this, i -> i.isMisconfigured() ? 1 : 0)
                .description("1 when dms.ocr.enabled is true but dms.ocr.job-token-secret is blank, else 0")
                .register(meterRegistry);
    }

    private boolean isMisconfigured() {
        return ocrProperties.isEnabled() && !jobTokenCodec.isConfigured();
    }

    @PostConstruct
    void warnAtStartupIfMisconfigured() {
        if (isMisconfigured()) {
            log.warn("dms.ocr.enabled is true but dms.ocr.job-token-secret is blank - no OCR job can ever be "
                    + "granted a read. Every upload will have its OCR request held back for the backlog sweeper "
                    + "until the secret is set. See /actuator/health (component 'ocrJobToken').");
        }
    }

    @Override
    public Health health() {
        if (isMisconfigured()) {
            return Health.down()
                    .withDetail("reason", "dms.ocr.enabled is true but dms.ocr.job-token-secret is blank")
                    .withDetail("effect", "OCR requests are declined and held for the backlog sweeper")
                    .build();
        }
        return Health.up().build();
    }
}
