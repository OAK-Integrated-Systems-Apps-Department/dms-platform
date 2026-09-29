package com.oak.dms.audit.helper;

import java.util.UUID;

/**
 * The fixed, well-known actor used for system-triggered audit events, so
 * actor_id is never null for these - null is reserved for "data missing",
 * not "the system did this".
 * <p>
 * SYSTEM_USER_ID is a reserved UUID, not a user: there is no users row
 * behind it (V24 seeded one; V37 removed it) and audit_events.actor_id has
 * no FK, so nothing ever resolves it. SYSTEM_DISPLAY_NAME is snapshotted
 * onto each event instead. Never assign this id to a real user.
 */
public final class SystemActor {

    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String SYSTEM_DISPLAY_NAME = "System";

    private SystemActor() {
    }
}
