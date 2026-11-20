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

    @Test
    void attachesFilesNextToTheLogoAndTheTwoBodiesWithTheirNamesAndBytesIntact() throws Exception {
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setHost("127.0.0.1");
        smtp.setPort(SMTP_PORT);
        BrandingProperties branding = new BrandingProperties("Ledger");
        BrandedMailLayout layout = new BrandedMailLayout(branding);
        BrandedMailLayout.Content content = BrandedMailLayout.Content.of("Report", List.of("Attached."));
        // RFC 4180 line endings: SMTP carries text parts with CRLF, so a CSV already in that form arrives byte for byte.
        byte[] csv = "a,b\r\n1,2\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] pdf = {'%', 'P', 'D', 'F', 0, 1, 2, (byte) 0xFF};

        new BrandedMailSender(smtp, branding).send("sender@example.com", "nimal@example.com", "Report",
                layout.html(content), layout.text(content), List.of(
                        new BrandedMailSender.Attachment("sales.csv", "text/csv", new org.springframework.core.io.ByteArrayResource(csv)),
                        new BrandedMailSender.Attachment("වාර්තාව.pdf", "application/pdf",
                                new org.springframework.core.io.ByteArrayResource(pdf))));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        List<BodyPart> parts = new ArrayList<>();
        collect((Multipart) received.getContent(), parts);
        // Still the same mail: both bodies and the inline logo.
        assertThat(parts).anySatisfy(p -> assertThat(p.getContentType()).startsWith("text/plain"));
        assertThat(parts).anySatisfy(p -> assertThat(p.getContentType()).startsWith("text/html"));
        assertThat(parts).anySatisfy(p -> assertThat(p.getHeader("Content-ID")).contains("<brand-logo>"));
        // Plus the two files, named as given (the Sinhala one survives the encoding) and byte for byte.
        BodyPart first = parts.stream().filter(p -> Part.ATTACHMENT.equals(dispositionOf(p)) && nameOf(p).equals("sales.csv"))
                .findFirst().orElseThrow();
        BodyPart second = parts.stream().filter(p -> Part.ATTACHMENT.equals(dispositionOf(p)) && nameOf(p).equals("වාර්තාව.pdf"))
                .findFirst().orElseThrow();
        assertThat(first.getInputStream().readAllBytes()).isEqualTo(csv);
        assertThat(second.getInputStream().readAllBytes()).isEqualTo(pdf);
        assertThat(parts.stream().filter(p -> Part.ATTACHMENT.equals(dispositionOf(p)))).hasSize(2);
    }

    @Test
    void theOriginalSendStillAttachesNothingButTheLogo() throws Exception {
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setHost("127.0.0.1");
        smtp.setPort(SMTP_PORT);
        BrandingProperties branding = new BrandingProperties("Ledger");
        BrandedMailLayout layout = new BrandedMailLayout(branding);
        BrandedMailLayout.Content content = BrandedMailLayout.Content.of("Plain", List.of("No files."));

        new BrandedMailSender(smtp, branding).send("sender@example.com", "nimal@example.com", "Plain",
                layout.html(content), layout.text(content));

        List<BodyPart> parts = new ArrayList<>();
        collect((Multipart) greenMail.getReceivedMessages()[0].getContent(), parts);
        assertThat(parts.stream().filter(p -> Part.ATTACHMENT.equals(dispositionOf(p)))).isEmpty();
    }

    private static String dispositionOf(BodyPart part) {
        try {
            return part.getDisposition();
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nameOf(BodyPart part) {
        try {
            return jakarta.mail.internet.MimeUtility.decodeText(part.getFileName());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
