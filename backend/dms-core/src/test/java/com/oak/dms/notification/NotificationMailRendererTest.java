package com.oak.dms.notification;

import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.notification.channel.email.NotificationMailRenderer;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.enums.NotificationTier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** N5: the HTML renderer keeps the subject, body facts and an absolute link, and escapes HTML. */
class NotificationMailRendererTest {

    private final NotificationMailRenderer renderer =
            new NotificationMailRenderer(new BrandingProperties("Oak DMS"), "https://app.example.com/");

    private Notification notification(String title, String body, String link) {
        return Notification.builder()
                .notificationId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .recipientUserId(UUID.randomUUID())
                .categoryCode("comment.mention")
                .tier(NotificationTier.standard)
                .title(title)
                .body(body)
                .linkUrl(link)
                .sourceModule("documents")
                .dedupeKey("k")
                .build();
    }

    @Test
    void rendersSubjectBodyAndAbsoluteLink() {
        NotificationMailRenderer.RenderedMail mail =
                renderer.render(notification("Approval needed: Contract", "Please review the contract.", "/documents/42"));

        assertThat(mail.subject()).isEqualTo("Approval needed: Contract");
        assertThat(mail.html()).contains("Please review the contract.")
                .contains("https://app.example.com/documents/42")
                .contains("Oak DMS");
        assertThat(mail.text()).contains("Please review the contract.")
                .contains("Open: https://app.example.com/documents/42");
    }

    @Test
    void escapesHtmlAndOmitsButtonWhenNoLink() {
        NotificationMailRenderer.RenderedMail mail =
                renderer.render(notification("A <b>doc</b> & you", "x < y", null));

        assertThat(mail.html()).contains("A &lt;b&gt;doc&lt;/b&gt; &amp; you").doesNotContain("<b>doc</b>");
        assertThat(mail.html()).doesNotContain(">Open<");
        assertThat(mail.text()).doesNotContain("Open:");
    }

    @Test
    void keepsAbsoluteLinksAsIs() {
        NotificationMailRenderer.RenderedMail mail =
                renderer.render(notification("t", "b", "https://other.example.com/x"));
        assertThat(mail.html()).contains("https://other.example.com/x");
    }
}
