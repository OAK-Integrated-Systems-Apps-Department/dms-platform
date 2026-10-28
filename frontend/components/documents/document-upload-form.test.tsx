import { describe, expect, it } from "vitest"

import { isRetryable, isTerminalStatus, phaseLabel } from "./document-upload-form"

/**
 * Phase 1 B.8: dms-core's ocr-status contract gained a "queued" state
 * (upload/rerun now set ocr_status='pending' -- queued, unclaimed -- and
 * only a real ocr-worker claim sets 'processing'), plus an `errorCode`
 * the client uses to decide whether a retry could ever succeed.
 */
describe("isTerminalStatus", () => {
  it("keeps polling while queued -- an unclaimed job is not a result", () => {
    expect(isTerminalStatus("queued")).toBe(false)
  })

  it("keeps polling while processing", () => {
    expect(isTerminalStatus("processing")).toBe(false)
  })

  it("stops polling on a real terminal result", () => {
    expect(isTerminalStatus("completed")).toBe(true)
    expect(isTerminalStatus("failed")).toBe(true)
  })

  it("keeps polling when no status has arrived yet", () => {
    expect(isTerminalStatus(undefined)).toBe(false)
  })
})

describe("phaseLabel", () => {
  it("shows queued distinctly from processing", () => {
    expect(phaseLabel("queued")).toBe("Queued…")
    expect(phaseLabel("processing")).toBe("Processing OCR…")
    expect(phaseLabel("queued")).not.toBe(phaseLabel("processing"))
  })

  it("labels the remaining phases", () => {
    expect(phaseLabel("uploading")).toBe("Uploading…")
    expect(phaseLabel("done")).toBe("Done")
    expect(phaseLabel("failed")).toBe("Failed")
    expect(phaseLabel("idle")).toBe("")
  })
})

describe("isRetryable", () => {
  it("hides retry for a permanently-unsupported format", () => {
    expect(isRetryable("UNSUPPORTED_FORMAT")).toBe(false)
  })

  it("allows retry for transient or file-specific failures", () => {
    expect(isRetryable("TIMEOUT")).toBe(true)
    expect(isRetryable("WORKER_CRASH")).toBe(true)
    expect(isRetryable("ENGINE_ERROR")).toBe(true)
    expect(isRetryable("CORRUPT_FILE")).toBe(true)
  })

  it("allows retry when no error code came back at all", () => {
    expect(isRetryable(null)).toBe(true)
    expect(isRetryable(undefined)).toBe(true)
  })
})
