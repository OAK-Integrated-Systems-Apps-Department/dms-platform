"use client"

import { useEffect } from "react"

import { refreshSession } from "@/lib/api-fetch"

const ACTIVITY_EVENTS = ["mousemove", "keydown", "click", "scroll", "touchstart"] as const

/** How often to check whether there's been enough recent activity to justify a proactive refresh. */
const CHECK_INTERVAL_MS = 5 * 60 * 1000

/**
 * How recently the user must have interacted with the page for a check to
 * trigger a refresh. Comfortably under both Keycloak's accessTokenLifespan
 * and ssoSessionIdleTimeout (access token 1800s/30 min; idle timeout 8 h since the realm file and bootstrap.sh set it - see
 * AuthCookieService's and AuditRequestContextFilter's own doc comments) -
 * as long as the user does anything at least this often, a check is never
 * more than CHECK_INTERVAL_MS away from firing a refresh, well inside
 * either deadline.
 */
const ACTIVITY_WINDOW_MS = 10 * 60 * 1000

/** Coming back to the tab renews the session when the last renewal is at least this old. */
const RETURN_REFRESH_AFTER_MS = 2 * 60 * 1000

/** How many pieces of long-running work currently want the session kept alive. */
let keepAliveCount = 0

/**
 * Marks long-running work (e.g. a queue of uploads) as active: while any is,
 * the periodic check refreshes the session even with no recent input and
 * the tab in the background, since the user is waiting on it, not gone.
 * Returns the function that ends it - safe to call more than once; always
 * call it when the work finishes, or the session never idles out.
 */
export function beginSessionKeepAlive(): () => void {
  keepAliveCount++
  let ended = false
  return () => {
    if (ended) return
    ended = true
    keepAliveCount = Math.max(0, keepAliveCount - 1)
  }
}

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
 * keep a session alive on its own either. The one exception is work
 * registered through `beginSessionKeepAlive` - while any is active, every
 * check refreshes regardless of activity or visibility.
 */
export function useProactiveSessionRefresh(): void {
  useEffect(() => {
    let lastActivityAt = Date.now()
    const markActive = () => {
      lastActivityAt = Date.now()
    }
    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, markActive, { passive: true, capture: true })
    }

    let lastRefreshAt = Date.now()
    const refresh = () => {
      lastRefreshAt = Date.now()
      void refreshSession()
    }

    const intervalId = window.setInterval(() => {
      const recentlyActive = Date.now() - lastActivityAt < ACTIVITY_WINDOW_MS
      if (keepAliveCount > 0 || (recentlyActive && document.visibilityState === "visible")) {
        refresh()
      }
    }, CHECK_INTERVAL_MS)

    // Coming back to the tab (it was covered, minimised or in another window, so the timer above skipped it, and
    // browsers also slow timers in hidden tabs): renew right away if it has been a while, instead of finding out
    // from a request that the token - and, with a short idle limit, the whole session - has already lapsed.
    const onReturn = () => {
      if (document.visibilityState === "visible" && Date.now() - lastRefreshAt >= RETURN_REFRESH_AFTER_MS) {
        markActive()
        refresh()
      }
    }
    document.addEventListener("visibilitychange", onReturn)
    window.addEventListener("focus", onReturn)

    return () => {
      for (const event of ACTIVITY_EVENTS) {
        window.removeEventListener(event, markActive, { capture: true })
      }
      window.clearInterval(intervalId)
      document.removeEventListener("visibilitychange", onReturn)
      window.removeEventListener("focus", onReturn)
    }
  }, [])
}
