import { describe, expect, it } from "vitest"

import { signAndApproveSchema } from "./schema"

const BASE = {
  comment: "",
  password: "hunter2",
  consent: true,
  visualKind: "typed" as const,
  visualText: "Jane Doe",
  visualDrawnImageBase64: null,
  visualUploadedImageBase64: null,
}

describe("signAndApproveSchema", () => {
  it("accepts a minimal valid submission (typed signature)", () => {
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

  it("requires typed text when visualKind is typed", () => {
    const result = signAndApproveSchema.safeParse({ ...BASE, visualText: "" })
    expect(result.success).toBe(false)
  })

  it("accepts a drawn signature with an image", () => {
    const result = signAndApproveSchema.safeParse({
      ...BASE,
      visualKind: "drawn",
      visualText: "",
      visualDrawnImageBase64: "data:image/png;base64,abc",
    })
    expect(result.success).toBe(true)
  })

  it("rejects a drawn signature without an image", () => {
    const result = signAndApproveSchema.safeParse({
      ...BASE,
      visualKind: "drawn",
      visualText: "",
      visualDrawnImageBase64: null,
    })
    expect(result.success).toBe(false)
  })

  it("accepts an uploaded signature with an image", () => {
    const result = signAndApproveSchema.safeParse({
      ...BASE,
      visualKind: "uploaded",
      visualText: "",
      visualUploadedImageBase64: "data:image/png;base64,abc",
    })
    expect(result.success).toBe(true)
  })

  it("rejects an uploaded signature without an image", () => {
    const result = signAndApproveSchema.safeParse({
      ...BASE,
      visualKind: "uploaded",
      visualText: "",
      visualUploadedImageBase64: null,
    })
    expect(result.success).toBe(false)
  })
})
