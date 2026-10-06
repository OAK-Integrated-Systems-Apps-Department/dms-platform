package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.service.WatermarkStyleValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configurable guardrails - the ones V68's CHECK constraints deliberately
 * sit outside of, so that tightening them later is a config change rather
 * than a migration.
 */
class WatermarkStyleValidatorTest {

    private final WatermarkProperties properties = new WatermarkProperties();
    private final WatermarkStyleValidator validator = new WatermarkStyleValidator(properties);

    @Test
    void nullMeansNotSuppliedAndSurvivesAsNull() {
        // Null is how both a template and a request say "fall through to the
        // system default", so validation must never turn one into a value.
        assertThat(validator.fontSize(null)).isNull();
        assertThat(validator.opacity(null)).isNull();
        assertThat(validator.rotation(null)).isNull();
        assertThat(validator.color(null)).isNull();
        assertThat(validator.placement(null)).isNull();
        assertThat(validator.pageScope(null)).isNull();
    }

    @Test
    void blankStringsAreTreatedAsNotSuppliedToo() {
        assertThat(validator.color("  ")).isNull();
        assertThat(validator.placement("")).isNull();
        assertThat(validator.pageScope("   ")).isNull();
    }

    @Test
    void valuesInsideTheConfiguredRangeArePassedThrough() {
        assertThat(validator.fontSize(8)).isEqualTo(8);
        assertThat(validator.fontSize(200)).isEqualTo(200);
        assertThat(validator.opacity(5)).isEqualTo(5);
        assertThat(validator.opacity(100)).isEqualTo(100);
        assertThat(validator.rotation(-90)).isEqualTo(-90);
        assertThat(validator.rotation(90)).isEqualTo(90);
        assertThat(validator.rotation(0)).isZero();
    }

    @Test
    void fontSizeOutsideTheRangeIsRejectedWithTheRangeInTheMessage() {
        assertThatThrownBy(() -> validator.fontSize(7))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("between 8 and 200")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> validator.fontSize(201)).isInstanceOf(WatermarkException.class);
    }

    @Test
    void opacityZeroIsRejectedSoAWatermarkCanNeverBeInvisible() {
        // The point of the floor: a user who set 0 would believe their copy
        // was marked when nothing had been drawn on it.
        assertThatThrownBy(() -> validator.opacity(0))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("opacity");
        assertThatThrownBy(() -> validator.opacity(4)).isInstanceOf(WatermarkException.class);
        assertThatThrownBy(() -> validator.opacity(101)).isInstanceOf(WatermarkException.class);
    }

    @Test
    void opacityFloorStaysAboveZeroEvenIfSomebodyConfiguresZero() {
        properties.getStyle().setMinOpacity(0);
        assertThatThrownBy(() -> validator.opacity(0)).isInstanceOf(WatermarkException.class);
        assertThat(validator.opacity(1)).isEqualTo(1);
    }

    @Test
    void rotationOutsideTheRangeIsRejected() {
        assertThatThrownBy(() -> validator.rotation(-91)).isInstanceOf(WatermarkException.class);
        assertThatThrownBy(() -> validator.rotation(91)).isInstanceOf(WatermarkException.class);
    }

    @Test
    void theRangesComeFromConfigurationRatherThanBeingCompiledIn() {
        properties.getStyle().setMaxFontSize(64);
        assertThatThrownBy(() -> validator.fontSize(100))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("between 8 and 64");
        assertThat(validator.fontSize(64)).isEqualTo(64);
    }

    @Test
    void colourMustBeSixDigitHexAndIsNormalisedToLowercase() {
        assertThat(validator.color("#AABBCC")).isEqualTo("#aabbcc");
        assertThat(validator.color("  #808080  ")).isEqualTo("#808080");
    }

    @Test
    void colourRejectsNamesShorthandAndAlpha() {
        for (String bad : new String[]{"red", "808080", "#80808", "#8080808", "#GGGGGG", "#abc", "rgb(1,2,3)"}) {
            assertThatThrownBy(() -> validator.color(bad))
                    .describedAs("colour '%s'", bad)
                    .isInstanceOf(WatermarkException.class)
                    .hasMessageContaining("hex colour");
        }
    }

    @Test
    void placementAndPageScopeParseTheHyphenatedWireValues() {
        assertThat(validator.placement("tiled")).isEqualTo(WatermarkPlacement.TILED);
        assertThat(validator.placement("header-footer")).isEqualTo(WatermarkPlacement.HEADER_FOOTER);
        assertThat(validator.pageScope("all-pages")).isEqualTo(WatermarkPageScope.ALL_PAGES);
        assertThat(validator.pageScope("first-page-only")).isEqualTo(WatermarkPageScope.FIRST_PAGE_ONLY);
    }

    @Test
    void theConstantNameIsNotAcceptedAsAWireValue() {
        // HEADER_FOOTER is the Java name; the API and the database both store
        // "header-footer". Accepting both would mean two spellings of one
        // value ending up in request_snapshot.
        assertThatThrownBy(() -> validator.placement("HEADER_FOOTER"))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("tiled, centered, corner, header-footer");
        assertThatThrownBy(() -> validator.pageScope("FIRST_PAGE_ONLY")).isInstanceOf(WatermarkException.class);
    }

    @Test
    void anUnknownPlacementOrScopeIsRejectedRatherThanFallingBackToTheDefault() {
        assertThatThrownBy(() -> validator.placement("diagonal")).isInstanceOf(WatermarkException.class);
        assertThatThrownBy(() -> validator.pageScope("last-page")).isInstanceOf(WatermarkException.class);
    }
}
