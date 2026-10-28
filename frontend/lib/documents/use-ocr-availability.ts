"use client"

import { useQuery } from "@tanstack/react-query"

import { ApiError } from "@/lib/api-error"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch } from "@/lib/api-fetch"

/** How long the probe's answer is trusted before it is checked again. */
const STALE_MS = 5 * 60 * 1000

/**
 * Whether OCR can be offered at all on this deployment.
 *
 * {@code dms.ocr.enabled} is off by default, and when it is off the worker may
 * not even be deployed - so every OCR entry point has to hide rather than
 * offer a screen with nothing behind it. Mirrors
 * {@code useWatermarkAvailability}, and for the same reason: a capability that
 * can be switched off server-side needs a client-side answer to "is it on",
 * and holding the permission is not that answer.
 *
 * Probed with the review queue rather than a dedicated endpoint:
 * <ul>
 *   <li><b>200</b> - on.</li>
 *   <li><b>404</b> - the route isn't mapped; OCR is off for this deployment.</li>
 *   <li><b>403</b> - on, but not for this caller. Treated as off for the
 *       purpose of showing links: there is nothing here they may use, and
 *       letting it throw would put a failed query behind every screen.</li>
 * </ul>
 * Anything else is left to throw, so a real failure is still a real failure.
 *
 * `size=1` because the answer is the status code, not the rows - there is no
 * reason to make the server page 25 review items to find out whether a feature
 * is switched on.
 */
export function useOcrAvailability() {
  return useQuery<boolean>({
    queryKey: ["ocr", "availability"],
    staleTime: STALE_MS,
    retry: false,
    queryFn: async () => {
      try {
        await apiFetch(
          `${API_BASE_URL}/api/v1/documents/review-queue?page=0&size=1`,
          undefined,
          "Could not check whether OCR is available."
        )
        return true
      } catch (error) {
        if (error instanceof ApiError && (error.status === 404 || error.status === 403)) {
          return false
        }
        throw error
      }
    },
  })
}
