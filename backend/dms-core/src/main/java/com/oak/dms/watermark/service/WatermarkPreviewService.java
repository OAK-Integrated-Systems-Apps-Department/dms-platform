package com.oak.dms.watermark.service;

import com.oak.dms.watermark.config.WatermarkAvailability;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkPreviewRequest;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.RenderTarget;
import com.oak.dms.watermark.format.WatermarkRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a sample watermark as a PNG, so an administrator tuning a template
 * can see what they are about to inflict on every document in the tenant.
 *
 * <h2>Sample values, not the caller's own</h2>
 * Placeholders are filled with obvious stand-ins ({@code jdoe},
 * {@code 2026-01-31 09:00 UTC}) rather than resolved for real. Two reasons,
 * and the second is the one that matters: a preview is a picture of a
 * CONFIGURATION, so it should look the same to everyone who opens the
 * screen, and a preview that quietly embedded the viewer's own email address
 * would be an odd thing to screenshot into a ticket.
 *
 * <h2>Why this creates nothing</h2>
 * No job, no document read, no storage. That is what makes it safe to call
 * on every keystroke in a settings form - and also why it needs its own rate
 * limit that cannot fail open: there is no job cap or executor queue behind
 * it to absorb abuse. See {@link WatermarkRateLimiter}.
 */
@Service
@RequiredArgsConstructor
public class WatermarkPreviewService {

    /** A fixed landscape strip - a preview is a swatch, not a page. */
    private static final double PREVIEW_WIDTH_POINTS = 420;
    private static final double PREVIEW_HEIGHT_POINTS = 150;

    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    /**
     * Recognisably fake, and recognisably the right SHAPE - a short login
     * name, a plausible email, a full timestamp - so the preview shows how
     * much room a real value will take.
     */
    private static final Map<String, String> SAMPLES = Map.of(
            "username", "jdoe",
            "userEmail", "jdoe@example.com",
            "date", "2026-01-31 09:00 UTC",
            "documentId", "00000000-0000-4000-8000-000000000000",
            "documentVersion", "3",
            "watermarkId", "a1b2c3d4");

    private final WatermarkTemplateService templateService;
    private final WatermarkStyleValidator styleValidator;
    private final TextSanitizer sanitizer;
    private final WatermarkRenderer renderer;
    private final WatermarkAvailability availability;
    private final WatermarkProperties properties;

    /** @return PNG bytes */
    @Transactional(readOnly = true)
    public byte[] render(WatermarkPreviewRequest request) {
        availability.require();

        boolean hasText = request.hasCustomText();
        if (request.hasTemplate() == hasText) {
            throw WatermarkException.badRequest("WATERMARK_TEXT_OR_TEMPLATE",
                    request.hasTemplate()
                            ? "Choose a template or type your own text, not both."
                            : "Choose a watermark template, or type the text you want.");
        }

        WatermarkTemplate template = request.hasTemplate()
                // Visible, not usable: an admin previewing a template they
                // have just retired should still see it.
                ? templateService.requireVisibleForPreview(request.templateId())
                : null;
        String source = template != null ? template.getDefaultText() : request.customText();

        WatermarkProperties.Style defaults = properties.getStyle();
        WatermarkSpec spec = new WatermarkSpec(
                sampleText(source),
                first(styleValidator.fontSize(request.fontSize()),
                        template == null ? null : template.getFontSize(), defaults.getFontSize()),
                first(styleValidator.opacity(request.opacity()),
                        template == null ? null : template.getOpacity(), defaults.getOpacity()),
                first(styleValidator.rotation(request.rotation()),
                        template == null ? null : template.getRotation(), defaults.getRotation()),
                first(styleValidator.color(request.color()),
                        template == null ? null : template.getColor(),
                        defaults.getColor().toLowerCase(Locale.ROOT)),
                first(styleValidator.placement(request.placement()),
                        template == null ? null : template.getPlacement(),
                        WatermarkPlacement.fromValue(defaults.getPlacement())),
                first(styleValidator.pageScope(request.pageScope()),
                        template == null ? null : template.getPageScope(),
                        WatermarkPageScope.fromValue(defaults.getPageScope())));

        return renderer.renderPng(spec, RenderTarget.ofPoints(PREVIEW_WIDTH_POINTS, PREVIEW_HEIGHT_POINTS,
                properties.getLimits().getRenderDpi()));
    }

    /**
     * Substitutes sample values. An unknown token is still a 400 here, for
     * the same reason it is on a real request - better to find the typo on
     * the settings screen than on a document.
     */
    private String sampleText(String template) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String token = matcher.group(1);
            String sample = SAMPLES.get(token);
            if (sample == null) {
                throw WatermarkException.badRequest("WATERMARK_UNKNOWN_PLACEHOLDER",
                        "'{" + token + "}' isn't a watermark placeholder. Available: "
                                + PlaceholderResolver.available() + ".");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(sample));
        }
        matcher.appendTail(result);
        String text = sanitizer.text(result.toString());
        if (text.isBlank()) {
            throw WatermarkException.badRequest("WATERMARK_TEXT_EMPTY",
                    "There's no watermark text to preview.");
        }
        return text;
    }

    private static <T> T first(T fromRequest, T fromTemplate, T systemDefault) {
        return fromRequest != null ? fromRequest : fromTemplate != null ? fromTemplate : systemDefault;
    }
}
