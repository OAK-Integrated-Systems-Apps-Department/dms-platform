package com.oak.dms.reporting.schedule;

import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.JobIdentity;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Whether a particular person may run reports right now - an administrator through their realm role, everyone else through their
 * roles - asked the way the platform asks for a background job: bound to that person, checked live, never from a cache.
 * <p>
 * Each question runs on its own pooled thread, because binding someone replaces {@code TenantContext}, and the caller may be a signed-in
 * request that must keep its own identity. The context is cleared before the thread goes back to the pool.
 * <p>
 * If the identity provider cannot be reached the platform raises {@link IllegalStateException}; it propagates, and the caller treats
 * it as "not now" rather than as "no access" - a passing outage must not skip a schedule or drop a recipient.
 */
@Component
public class ReportAccessProbe {

    public enum Result { OK, INACTIVE, NO_PERMISSION }

    private final JobIdentity jobIdentity;
    private final AuthorizationService authorization;
    private final TaskExecutor executor;

    public ReportAccessProbe(JobIdentity jobIdentity, AuthorizationService authorization,
                             @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.jobIdentity = jobIdentity;
        this.authorization = authorization;
        this.executor = executor;
    }

    public Result check(UUID tenantId, UUID userId) {
        return check(tenantId, userId, new String[0]);
    }

    /** As {@link #check(UUID, UUID)}, and the person must also hold every one of {@code alsoRequired}. */
    public Result check(UUID tenantId, UUID userId, String... alsoRequired) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!jobIdentity.bind(tenantId, userId)) {
                        return Result.INACTIVE;
                    }
                    boolean may = authorization.hasPermission(PermissionCodes.CRG_RUN)
                            && authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW);
                    for (String code : alsoRequired) {
                        may = may && authorization.hasPermission(code);
                    }
                    return may ? Result.OK : Result.NO_PERMISSION;
                } finally {
                    TenantContext.clear();
                }
            }, executor).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
