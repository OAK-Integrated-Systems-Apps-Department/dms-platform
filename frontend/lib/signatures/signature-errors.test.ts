import { describe, expect, it } from "vitest"

import { ApiError } from "@/lib/api-error"

import {
  isSignatureLocked,
  isSignatureRequired,
  isStepUpFailure,
  signatureErrorMessage,
  SIGNATURE_ERROR_CODES,
} from "./signature-errors"

describe("signature-errors", () => {
  it("isStepUpFailure recognizes only SIGNATURE_STEP_UP_FAILED", () => {
    expect(isStepUpFailure(new ApiError(400, "nope", SIGNATURE_ERROR_CODES.STEP_UP_FAILED))).toBe(true)
    expect(isStepUpFailure(new ApiError(400, "nope", SIGNATURE_ERROR_CODES.OTP_REQUIRED))).toBe(false)
    expect(isStepUpFailure(new Error("not an ApiError"))).toBe(false)
  })

  it("isSignatureRequired recognizes only WORKFLOW_SIGNATURE_REQUIRED", () => {
    expect(isSignatureRequired(new ApiError(409, "nope", SIGNATURE_ERROR_CODES.WORKFLOW_SIGNATURE_REQUIRED))).toBe(true)
    expect(isSignatureRequired(new ApiError(409, "nope", SIGNATURE_ERROR_CODES.DOCUMENT_SIGNATURE_LOCKED))).toBe(false)
  })

  it("isSignatureLocked recognizes only DOCUMENT_SIGNATURE_LOCKED", () => {
    expect(isSignatureLocked(new ApiError(409, "nope", SIGNATURE_ERROR_CODES.DOCUMENT_SIGNATURE_LOCKED))).toBe(true)
    expect(isSignatureLocked(new ApiError(409, "nope", SIGNATURE_ERROR_CODES.WORKFLOW_SIGNATURE_REQUIRED))).toBe(false)
  })

  it("signatureErrorMessage special-cases step-up failure", () => {
    expect(signatureErrorMessage(new ApiError(400, "server message", SIGNATURE_ERROR_CODES.STEP_UP_FAILED))).toBe(
      "Your password or authenticator app code is incorrect."
    )
  })

  it("signatureErrorMessage falls back to the server's own message for an unrecognized code", () => {
    expect(signatureErrorMessage(new ApiError(500, "something specific", "SOME_OTHER_CODE"))).toBe("something specific")
  })

  it("signatureErrorMessage falls back to a generic message for a non-ApiError", () => {
    expect(signatureErrorMessage(new Error("boom"))).toBe("Something went wrong. Please try again.")
  })
})
