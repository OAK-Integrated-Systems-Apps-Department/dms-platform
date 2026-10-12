package com.oak.dms.importexport;

import com.oak.dms.audit.filter.AuditRequestContext;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A mocked {@link AuditService} whose {@code event(..)} hands out real
 * builders, so a test can read back exactly what each call site recorded.
 */
public final class AuditTestSupport {

    private AuditTestSupport() {
    }

    public static AuditService mockAuditService() {
        AuditService auditService = mock(AuditService.class);
        when(auditService.event(anyString()))
                .thenAnswer(invocation -> new AuditEventBuilder(auditService, invocation.getArgument(0)));
        return auditService;
    }

    /** Every event logged through {@code auditService} so far, in order. */
    public static List<AuditEventBuilder> recorded(AuditService auditService) {
        ArgumentCaptor<AuditEventBuilder> captor = ArgumentCaptor.forClass(AuditEventBuilder.class);
        verify(auditService, atLeast(0)).persist(captor.capture());
        return captor.getAllValues();
    }

    /**
     * From now on, the correlation id bound when each event is logged, in
     * order - what {@code AuditService#persist} itself would record.
     */
    public static List<UUID> correlationIdsAtLog(AuditService auditService) {
        List<UUID> ids = new ArrayList<>();
        when(auditService.persist(any())).thenAnswer(invocation -> {
            ids.add(currentCorrelationIdOrNull());
            return null;
        });
        return ids;
    }

    /** The bound audit correlation id, or null if nothing is bound. */
    public static UUID currentCorrelationIdOrNull() {
        AuditRequestContext context = AuditRequestContext.currentOrNull();
        return context != null ? context.getCorrelationId() : null;
    }
}
