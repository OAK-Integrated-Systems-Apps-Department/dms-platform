package com.oak.dms.watermark.service;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks the style values a template or a watermark request supplies, against
 * the configurable guardrails in {@code dms.watermark.style.*}.
 * <p>
 * It lives apart from both callers because they need exactly the same rules
 * for different reasons: a template stores the values for later, a request
 * uses them now, and a guardrail that applied to only one of the two would
 * be a way around it. Every method takes null to mean "not supplied" and
 * returns null, because null is how both carry "fall through to the default"
 * - validation and defaulting are deliberately separate steps, so an
 * out-of-range value is an error rather than being quietly clamped to the
 * nearest legal one.
 */
@Component
@RequiredArgsConstructor
public class WatermarkStyleValidator {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final WatermarkProperties properties;

    public Integer fontSize(Integer value) {
        WatermarkProperties.Style style = properties.getStyle();
        return range(value, style.getMinFontSize(), style.getMaxFontSize(), "fontSize", "WATERMARK_INVALID_FONT_SIZE");
    }

    /**
     * Opacity's floor is configurable but never 0: a watermark nobody can
     * see is worse than none, because the person who asked for it believes
     * their copy is marked.
     */
    public Integer opacity(Integer value) {
        WatermarkProperties.Style style = properties.getStyle();
        return range(value, Math.max(1, style.getMinOpacity()), style.getMaxOpacity(), "opacity",
                "WATERMARK_INVALID_OPACITY");
    }

    public Integer rotation(Integer value) {
        WatermarkProperties.Style style = properties.getStyle();
        return range(value, style.getMinRotation(), style.getMaxRotation(), "rotation", "WATERMARK_INVALID_ROTATION");
    }

    /** Normalised to lowercase so {@code #ABCDEF} and {@code #abcdef} are one value, not two. */
    public String color(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!HEX_COLOR.matcher(trimmed).matches()) {
            throw WatermarkException.badRequest("WATERMARK_INVALID_COLOR",
                    "color must be a hex colour like #808080 - got '" + trimmed + "'.");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public WatermarkPlacement placement(String value) {
        return value == null || value.isBlank() ? null : WatermarkPlacement.fromValue(value.trim());
    }

    public WatermarkPageScope pageScope(String value) {
        return value == null || value.isBlank() ? null : WatermarkPageScope.fromValue(value.trim());
    }

    private static Integer range(Integer value, int min, int max, String field, String code) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw WatermarkException.badRequest(code,
                    field + " must be between " + min + " and " + max + " - got " + value + ".");
        }
        return value;
    }
}
