package com.oak.dms.notification.service;

import com.oak.dms.notification.dto.NotificationView;
import com.oak.dms.notification.dto.PreferenceView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** The caller's own inbox and email preferences (3.2, N12). All scoped to the caller + tenant. */
public interface NotificationInboxService {

    Page<NotificationView> inbox(boolean unreadOnly, String categoryCode, Pageable pageable);

    long unreadCount();

    void markRead(java.util.UUID notificationId);

    int markAllRead();

    void archive(java.util.UUID notificationId);

    List<PreferenceView> preferences();

    void updatePreference(String categoryCode, boolean emailEnabled);
}
