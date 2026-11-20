import { describe, expect, it } from "vitest"

import { safeReturnPath } from "./safe-return-path"

describe("safeReturnPath - paths of this application are returned unchanged", () => {
  it.each([
    "/",
    "/documents",
    "/documents/123",
    "/documents/3f2b8c1e-5a7d-4e0a-9d77-0c1b2a3d4e5f/edit",
    "/tasks",
    "/notifications",
    "/s/example-token",
    "/portal",
    "/portal/accept",
    "/admin/users/42/activity",
    "/documents?folder=abc&page=2",
    "/documents#top",
    "/documents/report%20final.pdf",
    "/loginfo",
    "/errors",
    "/documents/login",
    "/admin/error-log",
    "/files/100%25",
  ])("%s", (path) => {
    expect(safeReturnPath(path)).toBe(path)
  })
})

describe("safeReturnPath - anything doubtful becomes /", () => {
  it.each([
    ["undefined", undefined],
    ["null", null],
    ["empty", ""],
    ["blank", "   "],
    ["a tab", "\t"],
    ["padded", " /documents"],
    ["trailing space", "/documents "],
    ["relative path", "documents"],
    ["https URL", "https://evil.example"],
    ["http URL", "http://evil.example/x"],
    ["protocol-relative", "//evil.example"],
    ["protocol-relative with path", "//evil.example/documents"],
    ["slash then backslash", "/\\evil.example"],
    ["backslash first", "\\evil.example"],
    ["backslash inside", "/documents\\..\\x"],
    ["encoded backslash", "/%5Cevil.example"],
    ["encoded backslash, lower case", "/%5cevil.example"],
    ["encoded protocol-relative", "/%2F%2Fevil.example"],
    ["encoded slash then slash", "/%2F/evil.example"],
    ["double-encoded slashes", "/%252F%252Fevil.example"],
    ["double-encoded backslash", "/%255Cevil.example"],
    ["double-encoded dots", "/%252e%252e/login"],
    ["javascript scheme", "javascript:alert(1)"],
    ["JavaScript scheme, upper case", "JAVASCRIPT:alert(1)"],
    ["data scheme", "data:text/html,<script>alert(1)</script>"],
    ["malformed percent-encoding", "/documents/%E0%A4%A"],
    ["lone percent", "/documents/%"],
    ["bad hex", "/documents/%zz"],
    ["NUL", "/documents\u0000"],
    ["encoded NUL", "/documents%00"],
    ["newline", "/documents\n/evil"],
    ["tab inside a protocol-relative", "/\t/evil.example"],
    ["carriage return", "/documents\r"],
    ["encoded newline", "/documents%0A"],
    ["too long", `/${"a".repeat(3000)}`],
  ] as const)("%s", (_label, value) => {
    expect(safeReturnPath(value)).toBe("/")
  })
})

describe("safeReturnPath - sign-in pages are never a destination (no loops)", () => {
  it.each([
    "/login",
    "/login/",
    "/login?from=/documents",
    "/login#x",
    "/LOGIN",
    "/Login/",
    "/login/anything",
    "/session-expired",
    "/session-expired/",
    "/login-timeout",
    "/reset-password",
    "/reset-password/step",
    "/update-password",
    "/update-password?token=abc",
    "/error",
    "/error?message=boom",
    "/error/details",
  ])("%s", (path) => {
    expect(safeReturnPath(path)).toBe("/")
  })

  it.each([
    ["dot segments", "/documents/../login"],
    ["dot segments to an auth page below the root", "/a/b/../../session-expired"],
    ["a single dot", "/./login"],
    ["more dots than the root", "/../../login"],
    ["encoded dots", "/%2e%2e/login"],
    ["encoded dots, mixed case", "/%2E%2e/login"],
    ["encoded letter", "/%6Cogin"],
    ["encoded letters", "/%6c%6f%67%69%6e/"],
    ["encoded dot segment before an auth page", "/documents/%2e%2e/error"],
  ])("normalised or encoded variants: %s", (_label, path) => {
    expect(safeReturnPath(path)).toBe("/")
  })
})

describe("safeReturnPath - invariants", () => {
  const samples = [
    "/", "/documents", "//x", "/\\x", "https://x", "javascript:x", "", "   ", "/login", "/%2F%2Fx", "/a/../b", "/./a",
    "/documents/../documents/1", "/%", "/%41", "/a%2Fb", "/a b",
  ]

  it("always returns a path with a single leading slash, never throws, and is idempotent", () => {
    for (const sample of samples) {
      const once = safeReturnPath(sample)
      expect(once.startsWith("/")).toBe(true)
      expect(once.startsWith("//")).toBe(false)
      expect(once).not.toMatch(/[\\\u0000-\u001f]/)
      expect(safeReturnPath(once)).toBe(once)
    }
  })

  it("resolves harmless dot segments to the canonical path", () => {
    expect(safeReturnPath("/documents/../documents/1")).toBe("/documents/1")
    expect(safeReturnPath("/./tasks")).toBe("/tasks")
  })
})
