package com.oak.dms.encryption;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emails the people who can rotate an organisation's master key when it is
 * due (the schedule's reminder, {@link KeyRotationScheduler}): everyone holding
 * de.manage-encryption-keys.rotate-key through a role, and the organisation's
 * tenant admins. Plain text, no key material - just that it is due and where to
 * do it. Best effort: a mail failure is logged and the audit event stands.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyRotationReminderMailer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final JavaMailSender mailSender;
    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final KeycloakUserService keycloakUserService;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${dms.scanning.notifications.mail-from:no-reply@oakdms.local}")
    private String mailFrom;

    /** @return how many people were emailed */
    public int send(UUID tenantId, OffsetDateTime dueAt, Integer intervalDays) {
        int sent = 0;
        for (User user : recipients(tenantId)) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(user.getEmail());
                message.setSubject("Your organisation's encryption key is due for rotation");
                message.setText("Hello " + nameOf(user) + ",\n\n"
                        + "Your organisation's master encryption key "
                        + (dueAt != null ? "was due for rotation on " + DAY.format(dueAt) : "is due for rotation")
                        + (intervalDays != null ? " (it is rotated every " + intervalDays + " days)" : "") + ".\n\n"
                        + "Rotating it takes a moment and nothing has to be re-uploaded: rotate the key, then re-wrap "
                        + "the existing keys onto it.\n\n"
                        + frontendBaseUrl + "/admin/encryption\n\n"
                        + "You'll be reminded again until it is rotated. To have it rotated automatically, turn on "
                        + "\"Rotate and re-wrap automatically\" on the same page.\n");
                mailSender.send(message);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not email the key rotation reminder to user {}: {}", user.getUserId(), e.getMessage());
            }
        }
        return sent;
    }

    /** Active users of the tenant who may rotate its key - by role permission, or as tenant admin. */
    List<User> recipients(UUID tenantId) {
        Map<UUID, User> byId = new LinkedHashMap<>();
        List<UUID> permitted = jdbc.queryForList("""
                select distinct ur.user_id from user_roles ur
                join roles r on r.role_id = ur.role_id and not r.is_deleted and r.status = 'active'
                join role_permissions rp on rp.role_id = r.role_id
                join permissions p on p.permission_id = rp.permission_id
                where ur.tenant_id = ? and p.permission_code = ?
                """, UUID.class, tenantId, "de.manage-encryption-keys.rotate-key");
        userRepository.findAllById(permitted).forEach(u -> byId.put(u.getUserId(), u));
        try {
            List<UUID> admins = keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN");
            for (UUID keycloakId : admins) {
                userRepository.findByKeycloakIdAndDeletedFalse(keycloakId)
                        .filter(u -> tenantId.equals(u.getTenantId()))
                        .ifPresent(u -> byId.put(u.getUserId(), u));
            }
        } catch (RuntimeException e) {
            log.warn("Could not list tenant admins for the key rotation reminder: {}", e.getMessage());
        }
        return byId.values().stream()
                .filter(u -> tenantId.equals(u.getTenantId()) && !u.isDeleted()
                        && u.getStatus() == User.UserStatus.active && u.getEmail() != null)
                .toList();
    }

    private static String nameOf(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName()
                : user.getEmail();
    }
}
