import { describe, expect, it } from "vitest"

import { signAndApproveSchema } from "./schema"

const BASE = { comment: "", password: "hunter2", totp: "", consent: true }

describe("signAndApproveSchema", () => {
  it("accepts a minimal valid submission (no comment, no OTP)", () => {
    const result = signAndApproveSchema.safeParse(BASE)
    expect(result.success).toBe(true)
  })

  it("requires a non-empty password", () => {
    const result = signAndApproveSchema.safeParse({ ...BASE, password: "" })
    expect(result.success).toBe(false)
  })

  it("requires explicit consent", () => {
    const result = signAndApproveSchema.safeParse({ ...BASE, consent: false })
    expect(result.success).toBe(false)
  })

  it("rejects a comment over 2000 characters", () => {
    const result = signAndApproveSchema.safeParse({ ...BASE, comment: "a".repeat(2001) })
    expect(result.success).toBe(false)
  })

  it("accepts a full 6-digit OTP", () => {
    const result = signAndApproveSchema.safeParse({ ...BASE, totp: "123456" })
    expect(result.success).toBe(true)
  })

  it("rejects a partial OTP", () => {
    const result = signAndApproveSchema.safeParse({ ...BASE, totp: "123" })
    expect(result.success).toBe(false)
  })
})
