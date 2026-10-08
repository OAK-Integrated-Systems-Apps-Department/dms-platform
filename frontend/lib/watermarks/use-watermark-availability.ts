"use client"

import { useQuery } from "@tanstack/react-query"

import { ApiError } from "@/lib/api-error"
import { fetchWatermarkTemplates } from "./api"

import type { WatermarkTemplateDto } from "./api"

/** How long the probe's answer is trusted before it is checked again. */
const STALE_MS = 5 * 60 * 1000

export interface WatermarkAvailability {
  /** Watermarking can be offered to THIS caller - mapped, and theirs to use. */
  present: boolean
  /**
   * Nothing has told us the server can't draw. NOT a positive assertion:
   * only the preview and request endpoints call the availability guard, so
   * this probe cannot see a failed font probe. The dialog is where a 503
   * actually surfaces - see `WatermarkPreview`.
   */
  usable: boolean
  /** The server's own explanation when it is present but not usable (503). */
  unavailableMessage: string | null
  templates: WatermarkTemplateDto[]
}

const ABSENT: WatermarkAvailability = {
  present: false,
  usable: false,
  unavailableMessage: null,
  templates: [],
}

/**
 * Whether watermarking can be offered at all, derived from
 * `GET /api/v1/watermark-templates` - the one call that answers it without
 * a dedicated endpoint, and which the dialog needs anyway.
 *
 * Four outcomes, each meaning something different:
 * <ul>
 *   <li><b>200</b> - the feature is on and working. Templates come back in
 *       the same call, so opening the dialog needs no second request.</li>
 *   <li><b>404</b> - `dms.watermark.enabled` is false, so the controllers
 *       aren't mapped at all. The feature does not exist on this
 *       deployment and every entry point hides, rather than offering
 *       something that will fail.</li>
 *   <li><b>403</b> - the feature is on but this caller holds none of the
 *       permissions the endpoint accepts (document-view, or either template
 *       capability). Treated exactly like 404 and NOT like an error: for
 *       this user there is nothing to show, and letting it throw would put
 *       a failed query behind every screen that probes availability, for a
 *       permission they were never meant to have.</li>
 *   <li><b>503 WATERMARK_UNAVAILABLE</b> - the feature exists but the
 *       server can't render text (no usable font). The action stays
 *       VISIBLE but disabled, carrying the server's own message: hiding it
 *       would tell an administrator nothing is wrong when something is.
 *       <b>This list does not currently answer 503</b> - the backend calls
 *       its availability guard only on the endpoints that actually draw
 *       (preview and request), so a font-less server still returns 200
 *       here. The branch is kept because answering 503 is the correct
 *       behaviour for this endpoint and costs nothing to honour if it ever
 *       does; meanwhile the dialog's preview is what surfaces a 503, and it
 *       blocks submitting when it does.</li>
 * </ul>
 *
 * Any other failure (401, network) is left to throw so the global handlers
 * in providers.tsx deal with it as they do everywhere else.
 *
 * Cached for {@link STALE_MS} and retried zero times: a 404 here is a
 * deployment fact, not a transient error, and retrying it three times on
 * every mount would just be noise in the network tab.
 */
export function useWatermarkAvailability() {
  return useQuery<WatermarkAvailability>({
    queryKey: ["watermarks", "availability"],
    staleTime: STALE_MS,
    retry: false,
    queryFn: async () => {
      try {
        const templates = await fetchWatermarkTemplates()
        return { present: true, usable: true, unavailableMessage: null, templates }
      } catch (error) {
        if (error instanceof ApiError && (error.status === 404 || error.status === 403)) {
          return ABSENT
        }
        if (error instanceof ApiError && error.status === 503) {
          return {
            present: true,
            usable: false,
            unavailableMessage: error.message,
            templates: [],
          }
        }
        throw error
      }
    },
  })
}
