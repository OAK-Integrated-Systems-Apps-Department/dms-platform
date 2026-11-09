package com.oak.dms.common.mail;

import com.oak.dms.common.config.BrandingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared email layout: branded header, escaped content, optional button and note. */
class BrandedMailLayoutTest {

    private final BrandedMailLayout layout = new BrandedMailLayout(new BrandingProperties("Ledger"));

    @Test
    void rendersBrandLogoHeadingParagraphsButtonAndNote() {
        BrandedMailLayout.Content content = BrandedMailLayout.Content
                .of("Reset your password", List.of("Hi Nimal,", "Use the button below."))
                .withButton("Reset password", "https://app.example.com/update-password?token=abc")
                .withNote("This link expires in 30 minutes.");

        String html = layout.html(content);
        String text = layout.text(content);

        assertThat(html).contains("cid:brand-logo").contains("Ledger").doesNotContain("DOCUMENT VAULT")
                .contains("Reset your password").contains("Hi Nimal,")
                .contains("href=\"https://app.example.com/update-password?token=abc\"")
                .contains(">Reset password<").contains("This link expires in 30 minutes.");
        assertThat(text).contains("Reset password: https://app.example.com/update-password?token=abc")
                .contains("This link expires in 30 minutes.").endsWith("Ledger");
    }

    @Test
    void escapesHtmlAndOmitsButtonAndNoteWhenAbsent() {
        String html = layout.html(BrandedMailLayout.Content.of("A <b>doc</b> & you", List.of("x < y")));

        assertThat(html).contains("A &lt;b&gt;doc&lt;/b&gt; &amp; you").contains("x &lt; y")
                .doesNotContain("<b>doc</b>").doesNotContain("Button not working");
    }
}
