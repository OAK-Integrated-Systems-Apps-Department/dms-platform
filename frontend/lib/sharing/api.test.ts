import { describe, expect, it } from "vitest"

import {
  defaultExpiryValue,
  deniedCopy,
  isPreviewableInline,
  linkContentUrl,
  linkStatusLabel,
  portalContentUrl,
  toIsoInstant,
} from "@/lib/sharing/api"

describe("deniedCopy", () => {
  it("gives every unusable state its own friendly wording", () => {
    const titles = (["expired", "revoked", "exhausted", "locked", "unavailable"] as const).map(
      (s) => deniedCopy(s).title
    )
    expect(new Set(titles).size).toBe(5)
  })
})

describe("linkStatusLabel", () => {
  it("flags an active link that needs review", () => {
    expect(linkStatusLabel({ status: "active", needsReview: true }).label).toBe("Needs review")
  })
  it("does not hide a revoked link behind the review flag", () => {
    expect(linkStatusLabel({ status: "revoked", needsReview: true }).label).toBe("Revoked")
  })
})

describe("content urls", () => {
  it("puts the access token, document and download flag in the query", () => {
    const url = linkContentUrl("tok/1", "acc", { documentId: "d1", download: true })
    expect(url).toContain("/api/v1/public/share/tok%2F1/content?")
    expect(url).toContain("access=acc")
    expect(url).toContain("documentId=d1")
    expect(url).toContain("download=true")
  })
  it("defaults to inline", () => {
    expect(linkContentUrl("t", "a")).toContain("download=false")
  })
  it("builds a portal url with the guest token as access", () => {
    const url = portalContentUrl("g", "d", false)
    expect(url).toContain("/api/v1/public/portal/content?")
    expect(url).toContain("access=g")
  })
})

describe("expiry helpers", () => {
  it("adds days in local time in the picker's format", () => {
    const base = new Date(2026, 0, 30, 9, 5)
    expect(defaultExpiryValue(7, base)).toBe("2026-02-06T09:05")
  })
  it("converts a local value to an ISO instant and blank to undefined", () => {
    expect(toIsoInstant("")).toBeUndefined()
    expect(toIsoInstant("2026-02-06T09:05")).toMatch(/^2026-02-0\dT/)
    expect(toIsoInstant("garbage")).toBeUndefined()
  })
})

describe("isPreviewableInline", () => {
  it("shows pdfs, images and office files, not archives", () => {
    expect(isPreviewableInline("application/pdf")).toBe(true)
    expect(isPreviewableInline("image/png")).toBe(true)
    expect(isPreviewableInline("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).toBe(true)
    expect(isPreviewableInline("application/zip")).toBe(false)
  })
})
