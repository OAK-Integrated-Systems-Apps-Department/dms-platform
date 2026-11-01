package com.oak.dms.security.auth;

import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.common.mail.BrandedMailLayout;
import com.oak.dms.common.mail.BrandedMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The "your password was changed" security notice, sent after a person sets a
 * new password themselves (profile screen or an emailed reset link). It exists
 * so an account holder learns at once if someone else changed their password.
 * <p>
 * Best-effort: the password is already changed by the time this runs, so a
 * mail failure is logged and never surfaced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordChangedMailer {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);

    private final BrandedMailSender mailSender;
    private final BrandingProperties branding;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${app.password-reset.mail-from}")
    private String mailFrom;

    public void send(String email, String firstName) {
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            String productName = branding.getProductName();
            String when = WHEN.format(OffsetDateTime.now(ZoneOffset.UTC));
            BrandedMailLayout.Content content = BrandedMailLayout.Content.of(
                            "Your password was changed", List.of(
                                    "Hi " + (firstName == null || firstName.isBlank() ? "there" : firstName) + ",",
                                    "The password for your " + productName + " account was changed on " + when + ". "
                                            + "You can now sign in with your new password."))
                    .withButton("Sign in to " + productName, frontendBaseUrl + "/login")
                    .withNote("Wasn't you? Reset your password straight away at "
                            + frontendBaseUrl + "/reset-password and let your administrator know, "
                            + "as someone else may have access to your account.");
            BrandedMailLayout layout = new BrandedMailLayout(branding);
            mailSender.send(mailFrom, email, "Your " + productName + " password was changed",
                    layout.html(content), layout.text(content));
        } catch (Exception e) {
            log.warn("Could not send the password-changed email: {}", e.getMessage());
        }
    }
}
