import { ApiError } from "@/lib/api-error"

import { WATERMARK_SUPPORTED_LABEL } from "./constants"

export interface WatermarkFailure {
  /** What to show the user. Prefers the server's own wording. */
  message: string
  /**
   * The form field this belongs next to, when the server's refusal is about
   * one particular input. Null means show it as a general error.
   */
  field: "customText" | "templateId" | "versionId" | null
  /** Seconds to wait, from Retry-After - 503/429 only. */
  retryAfterSeconds: number | null
}

/**
 * Turns a watermark API failure into something a person can act on.
 *
 * The backend already writes its messages for the requester (see
 * WatermarkException) - "This PDF is digitally signed. Adding a watermark
 * would break the signature..." is better copy than anything this file
 * could invent, so the server's message is used whenever there is one. What
 * this adds is placement: a 422 about the text belongs under the text box,
 * not in a toast at the corner of the screen, and that mapping needs the
 * machine-readable `code` rather than the prose.
 *
 * Only statuses with no useful server message get wording of their own.
 */
export function describeWatermarkError(error: unknown): WatermarkFailure {
  if (!(error instanceof ApiError)) {
    return {
      message: error instanceof Error ? error.message : "Something went wrong. Please try again.",
      field: null,
      retryAfterSeconds: null,
    }
  }

  const server = error.message
  const code = error.code ?? ""

  switch (error.status) {
    case 400:
      return { message: server, field: fieldForCode(code), retryAfterSeconds: null }

    case 403:
      return {
        message: "You don't have permission to watermark this document.",
        field: null,
        retryAfterSeconds: null,
      }

    case 404:
      return {
        message: "This document is no longer available.",
        field: null,
        retryAfterSeconds: null,
      }

    case 409:
      return {
        message:
          "A different watermark was already requested with this key. Close the dialog and try again.",
        field: null,
        retryAfterSeconds: null,
      }

    case 413:
      return { message: server, field: null, retryAfterSeconds: null }

    case 415:
      return {
        message: server || `This file type can't be watermarked. Supported: ${WATERMARK_SUPPORTED_LABEL}.`,
        field: null,
        retryAfterSeconds: null,
      }

    case 422:
      // Signed, password-protected, unrenderable script, text too long for
      // the page - all of them say exactly what is wrong already.
      return { message: server, field: fieldForCode(code), retryAfterSeconds: null }

    case 429:
      return {
        message:
          server ||
          "You've asked for too many watermarked copies just now. Wait a moment and try again.",
        field: null,
        retryAfterSeconds: null,
      }

    case 503:
      return { message: server, field: null, retryAfterSeconds: null }

    default:
      return { message: server, field: null, retryAfterSeconds: null }
  }
}

/**
 * Which control a refusal belongs under. Codes rather than prose, so
 * rewording a backend message never silently moves an error to the wrong
 * field.
 */
function fieldForCode(code: string): WatermarkFailure["field"] {
  switch (code) {
    case "WATERMARK_TEXT_TOO_LONG_FOR_PAGE":
    case "WATERMARK_NOT_RENDERABLE":
    case "WATERMARK_UNKNOWN_PLACEHOLDER":
    case "WATERMARK_TEXT_REQUIRED":
    case "WATERMARK_TEXT_TOO_LONG":
      return "customText"
    case "WATERMARK_TEMPLATE_NOT_FOUND":
    case "WATERMARK_TEMPLATE_INACTIVE":
      return "templateId"
    case "WATERMARK_VERSION_NOT_FOUND":
      return "versionId"
    default:
      return null
  }
}

/**
 * `Retry-After` from a 429/503, in seconds. The header is on the response,
 * which `apiFetch` has already consumed by the time an ApiError is thrown -
 * so this reads it from a Response the caller kept, and callers that
 * haven't got one simply pass nothing and get a generic message.
 */
export function retryAfterSeconds(response: Response | null | undefined): number | null {
  const header = response?.headers.get("Retry-After")
  if (!header) return null
  const seconds = Number.parseInt(header, 10)
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null
}
