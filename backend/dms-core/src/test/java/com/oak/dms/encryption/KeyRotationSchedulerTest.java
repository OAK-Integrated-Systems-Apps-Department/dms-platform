package com.oak.dms.encryption;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.encryption.KeyRotationPolicyStore.RotationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the schedule does with one tenant: rotate it, remind about it once, or leave it. */
class KeyRotationSchedulerTest {

    private final UUID tenant = UUID.randomUUID();
    private final KeyRotationPolicyStore policies = mock(KeyRotationPolicyStore.class);
    private final EncryptionAdminService admin = mock(EncryptionAdminService.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
    private final KeyRotationReminderMailer mailer = mock(KeyRotationReminderMailer.class);
    private final KeyRotationScheduler scheduler = new KeyRotationScheduler(policies, admin,
            new EncryptionProperties(), audit, mock(JdbcTemplate.class), mailer);

    private static RotationPolicy policy(boolean due, boolean autoRotate) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(400);
        return new RotationPolicy(365, autoRotate, true, since, null, since.plusDays(365), due, null);
    }

    @Test
    void aDueKeyWithAutoRotate_isRotatedAndReWrapped() {
        when(policies.get(tenant)).thenReturn(policy(true, true));
        when(admin.rotate(tenant, "schedule"))
                .thenReturn(new KeyEncryptionService.KeyInfo("openbao", "transit/x", 2, 1, true));

        scheduler.check(tenant);

        verify(admin).rotate(tenant, "schedule");
        verify(admin).startRewrapFor(tenant);
        verify(audit, never()).event(AuditActionCatalog.ENCRYPTION_KEY_ROTATION_DUE);
    }

    @Test
    void aDueKeyWithoutAutoRotate_isOnlyRemindedAbout_onceUntilTheReminderIsDueAgain() {
        when(policies.get(tenant)).thenReturn(policy(true, false));
        when(policies.claimReminder(any(), any())).thenReturn(true, false);

        scheduler.check(tenant);
        scheduler.check(tenant);

        verify(admin, never()).rotate(any(), any());
        verify(audit).event(AuditActionCatalog.ENCRYPTION_KEY_ROTATION_DUE); // once, not twice
        verify(mailer).send(eq(tenant), any(), eq(365)); // and emailed once
    }

    @Test
    void aKeyNotYetDue_isLeftAlone() {
        when(policies.get(tenant)).thenReturn(policy(false, true));

        scheduler.check(tenant);

        verify(admin, never()).rotate(any(), any());
        verify(policies, never()).claimReminder(any(), any());
    }
}
