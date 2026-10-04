package com.oak.dms.importexport;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import org.mockito.ArgumentCaptor;

import java.util.List;

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
}
