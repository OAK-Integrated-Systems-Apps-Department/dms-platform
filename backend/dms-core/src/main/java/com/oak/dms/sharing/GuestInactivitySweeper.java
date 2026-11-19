package com.oak.dms.sharing;

import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, switches off guest accounts nobody has signed in to for longer than their organisation's inactivity
 * period (alternate flow of 1.5). Idempotent, so running on two instances at once only repeats work that is
 * already done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestInactivitySweeper {

    private final TenantRepository tenantRepository;
    private final GuestService guestService;

    @Scheduled(cron = "${dms.sharing.guest-sweep-cron:0 30 3 * * *}")
    public void sweep() {
        int total = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            if (tenant.isDeleted()) {
                continue;
            }
            try {
                total += guestService.disableInactive(tenant.getTenantId());
            } catch (RuntimeException e) {
                log.warn("Guest inactivity sweep failed for tenant {}: {}", tenant.getTenantId(), e.getMessage());
            }
        }
        if (total > 0) {
            log.info("Guest inactivity sweep switched off {} guest account(s)", total);
        }
    }
}
