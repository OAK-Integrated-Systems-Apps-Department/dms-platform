package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkAvailability;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The difference between "switched off" and "switched on but can't run",
 * which has to stay visible to whoever is looking at the logs.
 * <p>
 * If the font probe failed and the feature simply 404'd, an operator would
 * go hunting for a missing deployment or a bad route - when the actual fix
 * is one line in the Dockerfile. A 503 that says the server is missing its
 * fonts points straight at it. Hence two different answers, and this test to
 * keep them two.
 */
class WatermarkAvailabilityTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void aJvmThatCanRenderTextIsAvailableAndLetsRequestsThrough() {
        WatermarkAvailability availability = new WatermarkAvailability(
                new FontProvider(new WatermarkProperties()));
        availability.probeFonts();

        assertThat(availability.isAvailable()).isTrue();
        assertThatCode(availability::require).doesNotThrowAnyException();
    }

    @Test
    void aFailedProbeTurnsEveryRequestIntoA503ThatNamesTheRealProblem() {
        FontProvider broken = mock(FontProvider.class);
        when(broken.probe()).thenReturn(false);

        WatermarkAvailability availability = new WatermarkAvailability(broken);
        availability.probeFonts();

        assertThat(availability.isAvailable()).isFalse();
        assertThatThrownBy(availability::require)
                .isInstanceOf(WatermarkException.class)
                // 503, not 404: the feature exists here, it just can't run.
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(availability::require)
                .hasMessageContaining("fonts");
    }

    @Test
    void requestsArrivingBeforeTheProbeHasRunAreAttemptedRatherThanRefused() {
        // The probe runs on ApplicationReadyEvent. A request in that window
        // should be tried - refusing pre-emptively would make every restart
        // briefly look like an outage.
        WatermarkAvailability availability = new WatermarkAvailability(
                new FontProvider(new WatermarkProperties()));

        assertThat(availability.isAvailable()).isTrue();
        assertThatCode(availability::require).doesNotThrowAnyException();
    }

    @Test
    void theProbeNeverThrows_soAStartupWithNoFontsStillStarts() {
        // A broken font stack must leave the application running and the
        // rest of the DMS usable, not take the whole service down.
        WatermarkProperties properties = new WatermarkProperties();
        properties.setFontFamilies(List.of("Definitely Not Installed"));

        assertThatCode(() -> new WatermarkAvailability(new FontProvider(properties)).probeFonts())
                .doesNotThrowAnyException();
    }
}
