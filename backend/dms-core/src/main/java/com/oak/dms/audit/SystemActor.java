package com.oak.dms.audit;

import java.util.UUID;

/**
 * The fixed, well-known actor used for system-triggered audit events (see
 * V24__seed_system_actor.sql), so actor_id is never null for these -
 * null is reserved for "data missing", not "the system did this".
 * SYSTEM_USER_ID is a real users.user_id row; there is deliberately no
 * live FK enforcing that (see the migration's comment), but it must
 * still always resolve for as long as this seed row exists.
 */
public final class SystemActor {

    public static final UUID PLATFORM_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String SYSTEM_DISPLAY_NAME = "System";

    private SystemActor() {
    }
}
