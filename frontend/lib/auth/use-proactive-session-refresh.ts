"use client"

import { useEffect } from "react"

import { refreshSession } from "@/lib/api-fetch"

const ACTIVITY_EVENTS = ["mousemove", "keydown", "click", "scroll", "touchstart"] as const

/** How often to check whether there's been enough recent activity to justify a proactive refresh. */
const CHECK_INTERVAL_MS = 5 * 60 * 1000

/**
 * How recently the user must have interacted with the page for a check to
 * trigger a refresh. Comfortably under both Keycloak's accessTokenLifespan
 * and ssoSessionIdleTimeout (1800s/30 min in this realm - see
 * AuthCookieService's and AuditRequestContextFilter's own doc comments) -
 * as long as the user does anything at least this often, a check is never
 * more than CHECK_INTERVAL_MS away from firing a refresh, well inside
 * either deadline.
 */
const ACTIVITY_WINDOW_MS = 10 * 60 * 1000

/**
 * Proactively renews the session (via the same `refreshSession` the 401
 * interceptor in lib/api-fetch.ts uses, so the two share one in-flight
 * guard and can never race each other into two concurrent
 * `/auth/refresh` calls) while the user is genuinely active - instead of
 * only ever finding out the access token died from a live request 401ing.
 *
 * This exists because the reactive-only interceptor can't fully cover one
 * real gap on its own: Keycloak's `ssoSessionIdleTimeout` defaults to the
 * SAME duration as `accessTokenLifespan` (1800s), and an ordinary
 * authenticated request never touches Keycloak's own session state (JWT
 * validation is fully local/stateless against the JWKS) - so a session
 * with zero refresh calls in that window has both its access token AND
 * its underlying Keycloak session expire at essentially the same moment.
 * A refresh attempted only after the access token has already died can
 * lose that race outright (confirmed live against a real realm: Keycloak
 * rejects it with `invalid_grant`, since the session backing the refresh
 * token is already gone too). Calling refresh well before that deadline,
 * whenever the user is actually doing something, resets Keycloak's
 * session-idle clock early enough that the race never happens.
 *
 * Deliberately gated on real activity, not just "the tab is open": an
 * abandoned tab must still be allowed to idle-expire exactly as before,
 * so this only refreshes when at least one of the listened-for events
 * fired within the last `ACTIVITY_WINDOW_MS` - checked every
 * `CHECK_INTERVAL_MS`, and skipped outright while the tab isn't visible
 * (`document.visibilityState`), so a minimized/backgrounded tab doesn't
 * keep a session alive on its own either.
 */
export function useProactiveSessionRefresh(): void {
  useEffect(() => {
    let lastActivityAt = Date.now()
    const markActive = () => {
      lastActivityAt = Date.now()
    }
    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, markActive, { passive: true })
    }

    const intervalId = window.setInterval(() => {
      const recentlyActive = Date.now() - lastActivityAt < ACTIVITY_WINDOW_MS
      if (recentlyActive && document.visibilityState === "visible") {
        void refreshSession()
      }
    }, CHECK_INTERVAL_MS)

    return () => {
      for (const event of ACTIVITY_EVENTS) {
        window.removeEventListener(event, markActive)
      }
      window.clearInterval(intervalId)
    }
  }, [])
}
