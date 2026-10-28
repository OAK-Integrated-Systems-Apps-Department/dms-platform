package com.oak.dms.notification.support;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Base for email-delivery ITs (3.2, N5/N8). Runs an in-process GreenMail SMTP
 * server and points {@code spring.mail.*} at it, so tests capture the real HTML
 * + text MimeMessages the {@code EmailChannelSender} produces. The dispatcher's
 * per-instance switch is OFF here, so no mail is sent except when a test drives
 * the dispatcher explicitly. Backoff is shrunk so retry tests are fast.
 */
public abstract class NotificationMailSupport {

    protected static final int SMTP_PORT = freePort();
    protected static GreenMail greenMail;

    @Autowired
    private JdbcTemplate mailSupportJdbc;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port for GreenMail", e);
        }
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP_PORT);
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("dms.notification.mail-from", () -> "notifications@oakdms.test");
        registry.add("dms.notification.dispatcher.enabled", () -> "false");
        registry.add("dms.notification.dispatcher.base-backoff", () -> "PT0.2S");
        registry.add("dms.notification.dispatcher.max-backoff", () -> "PT1S");
        registry.add("dms.notification.dispatcher.lease", () -> "PT30S");
        registry.add("dms.notification.dispatcher.max-attempts", () -> "3");
        registry.add("dms.notification.dispatcher.batch-size", () -> "4");
    }

    @BeforeAll
    static void startMail() {
        greenMail = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
    }

    @AfterAll
    static void stopMail() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @BeforeEach
    void purgeMail() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        // The suite shares one database and the dispatcher is off in tests, so email rows from earlier
        // tests and runs stay pending forever. A test that drives NotificationDispatcher.runOnce() claims
        // the OLDEST rows first - without this it would send (and be buried under) that backlog before
        // reaching its own mail. Retire it; each test then only ever dispatches its own deliveries.
        mailSupportJdbc.update("update notification_deliveries set status = 'skipped',"
                + " skip_reason = 'test_backlog', locked_until = null, updated_at = now()"
                + " where channel = 'email' and status in ('pending', 'sending')");
    }

    protected MimeMessage[] allMail() {
        return greenMail.getReceivedMessages();
    }

    protected boolean waitForMail(int count, long timeoutMs) {
        return greenMail.waitForIncomingEmail(timeoutMs, count);
    }

    protected List<MimeMessage> mailTo(String email) {
        return Arrays.stream(greenMail.getReceivedMessages())
                .filter(m -> recipients(m).contains(email))
                .collect(Collectors.toList());
    }

    protected static String subjectOf(MimeMessage m) {
        try {
            return m.getSubject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The full body (GreenMail joins the multipart text + html parts). */
    protected static String bodyOf(MimeMessage m) {
        return GreenMailUtil.getBody(m);
    }

    protected static List<String> recipients(MimeMessage m) {
        try {
            return Arrays.stream(m.getAllRecipients()).map(Object::toString).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
