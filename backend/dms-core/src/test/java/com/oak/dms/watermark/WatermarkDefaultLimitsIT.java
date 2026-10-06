package com.oak.dms.watermark;

import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped defaults, unmodified.
 * <p>
 * {@code WatermarkServiceIT} raises {@code min-font-size} so its "too long"
 * assertion isn't decided by rounding. That is reasonable for testing the
 * mechanism, but it leaves the question nobody wants to discover in
 * production unanswered: with the configuration we actually ship, does a
 * watermark that cannot fit produce a message a user can act on, or a
 * watermark too small to read?
 * <p>
 * Everything here therefore runs on {@code dms.watermark.*} exactly as
 * {@code application.yml} defines it, with only the feature flag on.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkDefaultLimitsIT extends WatermarkFixtures {

    @Autowired
    private WatermarkService watermarkService;

    @Test
    void withShippedDefaultsAVeryLongWatermarkIsRefusedWithAMessageTheUserCanActOn() {
        // At the 200-character cap the mark has to shrink so far to fit an
        // A4 page that it would pass under the 8pt floor. The user is told
        // what to change rather than handed an unreadable copy.
        Fixture fixture = fixture();
        String tooLong = ("CONFIDENTIAL - DO NOT DISTRIBUTE OUTSIDE THE COMPANY - PROPERTY OF THE ORGANISATION - "
                + "ANY UNAUTHORISED COPYING OR FORWARDING OF THIS DOCUMENT IS A BREACH OF POLICY AND WILL BE "
                + "INVESTIGATED BY THE COMPLIANCE TEAM IMMEDIATELY");

        WatermarkException thrown = (WatermarkException) assertThatThrownBy(() ->
                watermarkService.apply(fixture.documentId(), customText(tooLong), null))
                .isInstanceOf(WatermarkException.class)
                .actual();

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(thrown.getCode()).isEqualTo("WATERMARK_TEXT_TOO_LONG_FOR_PAGE");
        // Names both levers the person actually has.
        assertThat(thrown.getMessage()).contains("Shorten the text").contains("smaller font size");
        assertThat(countJobsFor(fixture.documentId())).isZero();
    }

    @Test
    void withShippedDefaultsAnOrdinaryWatermarkIsAccepted() {
        // The other side of the line: the defaults must not be so tight that
        // a normal mark with a name and a date is refused.
        Fixture fixture = fixture();
        assertThat(watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL - {username} - {date}"), null).jobId()).isNotNull();
    }

    @Test
    void withShippedDefaultsTheLargestAllowedFontOnAShortMarkIsStillAccepted() {
        // 200pt is the configured maximum; it must be usable, not merely
        // accepted by the validator and then refused by the fit check.
        Fixture fixture = fixture();
        assertThat(watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, "DRAFT", null, 200, null, null, null, null, null), null).jobId())
                .isNotNull();
    }
}
