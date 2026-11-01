package com.oak.dms.common.mail;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.oak.dms.common.config.BrandingProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A branded mail reaches a real SMTP server as text + HTML with the logo attached inline. */
class BrandedMailSenderTest {

    private static final int SMTP_PORT = 3925;

    private GreenMail greenMail;

    @BeforeEach
    void startMail() {
        greenMail = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
    }

    @AfterEach
    void stopMail() {
        greenMail.stop();
    }

    @Test
    void sendsHtmlAndTextWithTheLogoInlineAndTheProductAsSenderName() throws Exception {
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setHost("127.0.0.1");
        smtp.setPort(SMTP_PORT);
        BrandingProperties branding = new BrandingProperties("Ledger");
        BrandedMailLayout layout = new BrandedMailLayout(branding);
        BrandedMailLayout.Content content = BrandedMailLayout.Content
                .of("Welcome", List.of("Hi Nimal,")).withButton("Go", "https://app.example.com/x");

        new BrandedMailSender(smtp, branding).send("sender@example.com", "nimal@example.com", "Hello",
                layout.html(content), layout.text(content));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(received.getSubject()).isEqualTo("Hello");
        assertThat(((InternetAddress) received.getFrom()[0]).getPersonal()).isEqualTo("Ledger");

        List<BodyPart> parts = new ArrayList<>();
        collect((Multipart) received.getContent(), parts);
        assertThat(parts).anySatisfy(p -> assertThat(p.getContentType()).startsWith("text/plain"));
        assertThat(parts).anySatisfy(p -> assertThat(p.getContentType()).startsWith("text/html"));
        assertThat(parts).anySatisfy(p -> {
            assertThat(p.getContentType()).startsWith("image/png");
            assertThat(p.getHeader("Content-ID")).contains("<brand-logo>");
            assertThat(p.getDisposition()).isEqualTo(Part.INLINE);
        });
    }

    private static void collect(Multipart multipart, List<BodyPart> out) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContent() instanceof Multipart nested) {
                collect(nested, out);
            } else {
                out.add(part);
            }
        }
    }
}
