package com.oak.dms.watermark.config;

import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether this instance can actually produce a watermark, as opposed to
 * being configured to offer one.
 *
 * <h2>Two different "no", and they must not look alike</h2>
 * <ul>
 *   <li>{@code dms.watermark.enabled=false} - the feature is switched off.
 *       The controllers aren't even registered, so the endpoints 404. The
 *       feature does not exist here.</li>
 *   <li>The feature is on, but this JVM can't render text - no fontconfig,
 *       no font files, a broken image. The endpoints exist and answer
 *       <b>503 WATERMARK_UNAVAILABLE</b>.</li>
 * </ul>
 * Collapsing the second into the first would be actively harmful: an
 * operator seeing 404s goes looking for a missing deployment or a routing
 * rule, when the actual fix is one line in the Dockerfile. A 503 with this
 * message points straight at it.
 *
 * <h2>Why it is probed once, at startup</h2>
 * The runtime image is {@code eclipse-temurin:21-jre-alpine}, which ships no
 * fonts at all. Without this, the first person to ask for a watermark gets a
 * 500 from deep inside AWT, their job row says "failed" with nothing useful
 * in it, and the cause is invisible from the outside. Probing on
 * {@link ApplicationReadyEvent} turns that into one loud log line at boot,
 * before any user is involved.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
public class WatermarkAvailability {

    private final FontProvider fontProvider;
    /**
     * Optimistic until the probe says otherwise: the probe runs after the
     * context is ready, and a request arriving in that window should be
     * attempted rather than pre-emptively refused.
     */
    private final AtomicBoolean fontsUsable = new AtomicBoolean(true);

    public WatermarkAvailability(FontProvider fontProvider) {
        this.fontProvider = fontProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void probeFonts() {
        boolean usable = fontProvider.probe();
        fontsUsable.set(usable);
        if (!usable) {
            log.error("""
                    Watermarking is enabled but this JVM cannot render text, so every request will be \
                    refused with 503. The runtime image needs fontconfig and at least one font package \
                    - see backend/Dockerfile - and the JVM needs -Djava.awt.headless=true.""");
        }
    }

    public boolean isAvailable() {
        return fontsUsable.get();
    }

    /**
     * Guards every entry point that would end up drawing something. Called
     * at request time rather than only in the worker, so the person asking
     * finds out immediately instead of after a job has been queued, run and
     * failed.
     */
    public void require() {
        if (!fontsUsable.get()) {
            throw WatermarkException.unavailable("WATERMARK_UNAVAILABLE",
                    "Watermarking is temporarily unavailable on this server. "
                            + "Please tell your administrator - the server is missing its fonts.");
        }
    }
}
