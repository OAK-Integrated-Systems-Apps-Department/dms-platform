package com.oak.dms.audit;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.entity.AuditEvent;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.SystemActor;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves system-triggered audit events still record the reserved system
 * actor ({@code ...0001}, SYSTEM, "System") now that no users row backs it -
 * both when a caller opts in via {@code systemActor()} and when
 * {@code AuditService} falls back to it for lack of a bound user.
 */
@SpringBootTest
class SystemActorAuditIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private AuditService auditService;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void noBoundUser() {
        TenantContext.clear();
    }

    @Test
    void explicitSystemActor_isRecordedWithoutAUsersRow() {
        AuditEvent event = auditService.event(AuditActionCatalog.LOGIN_FAILURE)
                .systemActor()
                .objectType(AuditObjectTypes.USER)
                .resourceName("system-actor-it-" + UUID.randomUUID())
                .outcome(AuditOutcome.FAILURE)
                .log();

        assertSystemActor(event);
    }

    @Test
    void fallbackWithNoBoundUser_isRecordedAsTheSystemActor() {
        AuditEvent event = auditService.event(AuditActionCatalog.LOGIN_FAILURE)
                .objectType(AuditObjectTypes.USER)
                .resourceName("system-actor-it-" + UUID.randomUUID())
                .outcome(AuditOutcome.FAILURE)
                .log();

        assertSystemActor(event);
    }

    private void assertSystemActor(AuditEvent event) {
        assertThat(SystemActor.SYSTEM_USER_ID).isEqualTo(SYSTEM_USER_ID);
        assertThat(event.getActorId()).isEqualTo(SYSTEM_USER_ID);
        assertThat(event.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(event.getActorDisplayName()).isEqualTo("System");
        assertThat(event.getRecordHash()).isNotBlank();
        assertThat(userRepository.existsById(SYSTEM_USER_ID)).isFalse();
    }
}
