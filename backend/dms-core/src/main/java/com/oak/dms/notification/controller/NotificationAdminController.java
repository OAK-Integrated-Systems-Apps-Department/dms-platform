package com.oak.dms.notification.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.notification.dto.DeliveryLogView;
import com.oak.dms.notification.dto.TenantSettingView;
import com.oak.dms.notification.dto.UpdateTenantSettingRequest;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import com.oak.dms.notification.service.NotificationAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-admin notification administration (3.2, N12): category email defaults
 * and the delivery log with "retry now".
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification administration")
public class NotificationAdminController {

    private static final String MANAGE = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).NOTIF_SETTINGS_MANAGE)";
    private static final String VIEW_LOG = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).NOTIF_DELIVERY_LOG_VIEW)";
    private static final String RETRY = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).NOTIF_DELIVERY_LOG_RETRY)";
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationAdminService service;

    @GetMapping("/settings")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Category email defaults for the tenant (critical are locked)")
    public List<TenantSettingView> settings() {
        return service.settings();
    }

    @PutMapping("/settings/{categoryCode}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a standard/optional category's tenant email default (409 for critical)")
    public void updateSetting(@PathVariable("categoryCode") String categoryCode,
                              @Valid @RequestBody UpdateTenantSettingRequest request) {
        service.updateSetting(categoryCode, request.emailEnabledDefault());
    }

    @GetMapping("/deliveries")
    @PreAuthorize(VIEW_LOG)
    @Operation(summary = "Delivery log with optional status/channel filters, newest first")
    public PageResponse<DeliveryLogView> deliveries(@RequestParam(required = false) DeliveryStatus status,
                                                    @RequestParam(required = false) NotificationChannel channel,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(service.deliveries(status, channel,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    @PreAuthorize(RETRY)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Re-queue a failed or skipped delivery for another send")
    public void retry(@PathVariable("deliveryId") UUID deliveryId) {
        service.retry(deliveryId);
    }
}
