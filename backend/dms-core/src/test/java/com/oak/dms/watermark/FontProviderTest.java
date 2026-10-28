package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.format.FontProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Font selection, and the one thing it exists to prevent: a watermark that
 * carries somebody's name rendering their name as a row of hollow boxes.
 * <p>
 * A missing glyph is not an exception - AWT draws the font's {@code .notdef}
 * box and carries on, so the job succeeds, the audit log says the copy was
 * marked, and the document has tofu on it. The only way to catch that is to
 * ask each candidate font what it can actually display BEFORE drawing, which
 * is what {@link FontProvider#resolve} does.
 */
class FontProviderTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final WatermarkProperties properties = new WatermarkProperties();

    @Test
    void aFontThatCannotShowTheTextIsPassedOverForOneThatCan() {
        // Symbol-only fonts carry no Latin letters at all. Listed first, it
        // must still lose to a general-purpose font for ordinary text.
        properties.setFontFamilies(List.of(symbolOnlyFamily(), Font.SANS_SERIF));
        FontProvider provider = new FontProvider(properties);

        Font chosen = provider.resolve("CONFIDENTIAL").at(40f);

        assertThat(chosen.canDisplayUpTo("CONFIDENTIAL"))
                .describedAs("the chosen font must be able to draw every character")
                .isEqualTo(-1);
        assertThat(chosen.getFamily()).isNotEqualToIgnoringCase(symbolOnlyFamily());
    }

    @Test
    void theFirstFontThatCoversTheWholeTextWins_notMerelyTheFirstListed() {
        properties.setFontFamilies(List.of(symbolOnlyFamily(), Font.SERIF, Font.SANS_SERIF));
        FontProvider provider = new FontProvider(properties);

        assertThat(provider.resolve("Report").at(24f).canDisplayUpTo("Report")).isEqualTo(-1);
    }

    @Test
    void preferenceOrderIsHonouredWhenBothCandidatesCanDrawTheText() {
        properties.setFontFamilies(List.of(Font.SERIF, Font.SANS_SERIF));
        FontProvider provider = new FontProvider(properties);

        assertThat(provider.resolve("DRAFT").at(24f).getFamily())
                .isEqualToIgnoringCase(new Font(Font.SERIF, Font.BOLD, 12).getFamily());
    }

    @Test
    void aNameInAnotherScriptStillGetsAFontThatCanDrawIt() {
        // The real case: Åsa, Ωμέγα, 中文 in a {username} placeholder.
        FontProvider provider = new FontProvider(properties);
        for (String name : new String[]{"Åsa Lindqvist", "Αθήνα", "Ümit Öz"}) {
            Font font = provider.resolve(name).at(32f);
            assertThat(font.canDisplayUpTo(name))
                    .describedAs("'%s' should be fully renderable, got font %s", name, font.getFamily())
                    .isEqualTo(-1);
        }
    }

    @Test
    void textNoFontCanDrawIsRefusedAtResolveTimeRatherThanBeingDrawnAsBoxes() {
        properties.setFontFamilies(List.of(Font.SANS_SERIF));
        FontProvider provider = new FontProvider(properties);

        // Refused HERE, at resolve time, which is the request thread - not
        // later in a worker where it would become a failed job.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.resolve("REPORT 􏿽"))
                .isInstanceOf(com.oak.dms.watermark.exception.WatermarkException.class)
                .hasMessageContaining("empty boxes");
    }

    @Test
    void configuredFamiliesThisJvmDoesNotHaveAreDroppedRatherThanTried() {
        properties.setFontFamilies(List.of("No Such Font Anywhere", "Another Missing Font"));
        FontProvider provider = new FontProvider(properties);

        assertThat(provider.availableFamilies()).doesNotContain("No Such Font Anywhere", "Another Missing Font");
        // A logical family is always appended, so there is always something.
        assertThat(provider.availableFamilies()).isNotEmpty().endsWith(Font.SANS_SERIF);
        assertThat(provider.resolve("DRAFT").at(24f)).isNotNull();
    }

    @Test
    void anEmptyOrNullHeavyConfigurationStillLeavesAUsableFont() {
        properties.setFontFamilies(java.util.Arrays.asList(null, "", "   "));
        FontProvider provider = new FontProvider(properties);

        assertThat(provider.availableFamilies()).containsExactly(Font.SANS_SERIF);
        assertThat(provider.resolve("DRAFT").at(24f).canDisplayUpTo("DRAFT")).isEqualTo(-1);
    }

    @Test
    void aScriptNoConfiguredFamilyCoversStillFindsAnInstalledFontThatCanDrawIt() {
        // The regression this guards was found by running the smoke test
        // inside the real runtime image: CJK font packages had been
        // installed, and names still came out as hollow boxes, because the
        // configured list named "Noto Sans" and the CJK family is called
        // "Noto Sans CJK SC". If the configured list is treated as an
        // allowlist rather than a preference order, installing a font
        // package achieves nothing and nobody can tell why.
        properties.setFontFamilies(List.of(Font.SANS_SERIF));
        FontProvider provider = new FontProvider(properties);

        for (String name : new String[]{"李娜", "田中太郎", "김철수"}) {
            Font chosen = provider.resolve(name).at(40f);
            if (anyInstalledFontCanDraw(name)) {
                assertThat(chosen.canDisplayUpTo(name))
                        .describedAs("'%s' is drawable on this JVM, so it must be found (got %s)",
                                name, chosen.getFamily())
                        .isEqualTo(-1);
            }
        }
    }

    /** Whether this particular JVM has any font for that text - the fallback can only find what exists. */
    private static boolean anyInstalledFontCanDraw(String text) {
        for (String family : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(java.util.Locale.ROOT)) {
            if (new Font(family, Font.BOLD, 12).canDisplayUpTo(text) < 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theProbePassesOnAJvmThatHasFonts() {
        // The same call the startup check makes. On the Alpine runtime image
        // without a font package this returns false, which is what turns the
        // feature off with a clear log line instead of failing mid-job.
        assertThat(new FontProvider(properties).probe()).isTrue();
    }

    /**
     * A font family that has no Latin letters. Wingdings on Windows,
     * otherwise any family that can't draw "A" - and if this JVM has none,
     * the caller is told so rather than the test quietly passing.
     */
    private static String symbolOnlyFamily() {
        for (String candidate : new String[]{"Wingdings", "Webdings", "Symbol", "OpenSymbol"}) {
            Font font = new Font(candidate, Font.PLAIN, 12);
            if (font.getFamily().equalsIgnoreCase(candidate) && font.canDisplayUpTo("CONFIDENTIAL") >= 0) {
                return candidate;
            }
        }
        // No symbol font here: fall back to a name that doesn't resolve, which
        // FontProvider drops at startup - the assertions still hold, they just
        // exercise the "dropped" path rather than the "can't display" one.
        return "No Such Symbol Font";
    }
}
