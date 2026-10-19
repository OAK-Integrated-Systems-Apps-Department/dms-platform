package com.oak.dms.notification.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.notification.dto.NotificationView;
import com.oak.dms.notification.dto.PreferenceView;
import com.oak.dms.notification.dto.UnreadCountResponse;
import com.oak.dms.notification.dto.UpdatePreferenceRequest;
import com.oak.dms.notification.service.NotificationInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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
 * The caller's notification inbox and email preferences (3.2, N12). No permission
 * code: these act on the authenticated caller's own rows, like /tasks and /mentions.
 * The bell inbox and the profile preferences page call these.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationInboxController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationInboxService service;

    @GetMapping
    @Operation(summary = "The caller's notifications, newest first")
    public PageResponse<NotificationView> list(@RequestParam(defaultValue = "false") boolean unread,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(service.inbox(unread, category,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "The caller's unread, un-archived count (bell badge)")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(service.unreadCount());
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one notification read")
    public void markRead(@PathVariable("notificationId") UUID notificationId) {
        service.markRead(notificationId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark all the caller's notifications read")
    public void markAllRead() {
        service.markAllRead();
    }

    @PostMapping("/{notificationId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive one notification (removed from the inbox)")
    public void archive(@PathVariable("notificationId") UUID notificationId) {
        service.archive(notificationId);
    }

    @GetMapping("/preferences")
    @Operation(summary = "The caller's email preferences per category (critical are locked)")
    public List<PreferenceView> preferences() {
        return service.preferences();
    }

    @PutMapping("/preferences/{categoryCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn a standard/optional category's email on or off (409 for critical)")
    public void updatePreference(@PathVariable("categoryCode") String categoryCode,
                                 @Valid @RequestBody UpdatePreferenceRequest request) {
        service.updatePreference(categoryCode, request.emailEnabled());
    }
}
