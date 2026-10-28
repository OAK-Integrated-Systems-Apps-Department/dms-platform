package com.oak.dms.notification.service;

import java.util.List;
import java.util.UUID;

/**
 * The single entry point every module uses to notify people (3.2, decision 1).
 * Writes the in-app inbox row(s) and the delivery row(s) in the CALLER's
 * transaction, so a notification is never lost (committed with the change that
 * triggered it) nor phantom (rolled back with it). Delivery itself happens
 * out-of-band via the dispatcher. In-app is always created; email depends on
 * the recipient's resolved preference and whether they have an address.
 */
public interface NotificationService {

    /**
     * Create notifications for the request's recipients. Idempotent per
     * (tenant, recipient, dedupeKey): a recipient who already has a row with
     * the same dedupe key is skipped. Returns the ids actually created.
     */
    List<UUID> notify(NotificationRequest request);
}
