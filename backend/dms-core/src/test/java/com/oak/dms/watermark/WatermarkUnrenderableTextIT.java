package com.oak.dms.watermark;

import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontChoice;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Text this server has no font for is refused at REQUEST time, with nothing
 * written.
 *
 * <h2>Why the font layer is stubbed here</h2>
 * The refusal can only be reached with a real, assigned, non-format
 * character that no installed font covers - {@code TextSanitizer} strips
 * private-use, unassigned and surrogate code points before the font is ever
 * consulted, which is correct but closes the easy route. Which real scripts
 * are uncovered then depends entirely on the machine: an earlier version of
 * this test used Egyptian hieroglyphs behind an {@code Assumption} and
 * simply skipped on Windows, which meant the behaviour was unverified
 * exactly where developers run tests.
 * <p>
 * So the font layer answers "no font for this" deterministically, and what
 * is under test is what the SERVICE does with that answer: a 422 the caller
 * can act on, and no job row. Whether a given server really has a font for a
 * given script is a different question, answered by {@code FontProviderTest}
 * running inside the real runtime image in CI.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkUnrenderableTextIT extends WatermarkFixtures {

    private static final String UNCOVERED = "REPORT 𓀀";

    /**
     * A genuine {@link FontChoice}, minted by a real provider. The stub
     * below hands this back for ordinary text, so the only thing being
     * faked is the ANSWER to "is there a font for this?" - not the type the
     * rest of the code works with. {@code FontChoice}'s constructor stays
     * package-private, which is the point: only {@link FontProvider} mints
     * one, including here.
     */
    private static final FontChoice REAL_CHOICE =
            new FontProvider(new com.oak.dms.watermark.config.WatermarkProperties()).resolve("CONFIDENTIAL");

    @MockitoBean
    private FontProvider fontProvider;

    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;
    @Autowired
    private com.oak.dms.watermark.config.WatermarkAvailability availability;

    /**
     * A mocked FontProvider answers {@code false} to {@code probe()}, and the
     * startup probe has already run by the time a test does - so without
     * this every case here would fail as 503 UNAVAILABLE and never reach the
     * behaviour under test. Re-running the probe against the stub puts the
     * instance back into its normal state.
     */
    @org.junit.jupiter.api.BeforeEach
    void fontsAreUsable() {
        when(fontProvider.probe()).thenReturn(true);
        availability.probeFonts();
    }

    @Test
    void textWithNoFontIsA422AtRequestTimeAndCreatesNoJob() {
        when(fontProvider.resolve(anyString()))
                .thenReturn(REAL_CHOICE);
        when(fontProvider.resolve(UNCOVERED)).thenThrow(
                WatermarkException.unprocessable("WATERMARK_SCRIPT_UNSUPPORTED",
                        "The watermark text uses characters this server has no font for, so it would print as "
                                + "empty boxes. Ask your administrator to install a font package covering that "
                                + "script."));
        Fixture fixture = fixture();

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText(UNCOVERED), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("empty boxes")
                .hasMessageContaining("font package")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // Nothing queued, nothing to explain later, nothing counting against
        // the caller's active-job cap.
        assertThat(countJobsFor(fixture.documentId())).isZero();
        assertThat(auditCount("WATERMARK_REQUESTED", fixture.documentId())).isZero();
    }

    @Test
    void textTheServerCanDrawIsStillAccepted() {
        // Guards against the stub above turning into "everything fails".
        when(fontProvider.resolve(anyString()))
                .thenReturn(REAL_CHOICE);
        Fixture fixture = fixture();

        assertThat(watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null).jobId())
                .isNotNull();
    }
}
