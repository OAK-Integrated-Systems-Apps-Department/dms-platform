import { describe, expect, it } from "vitest"

import { SESSION_EXPIRED_FLAG, SIGN_IN_AGAIN_PATH, isSignInAgainRequest } from "./session-recovery"

const params = (query: string) => new URLSearchParams(query)

describe("session recovery - the sign-in-again flag", () => {
  it("'Sign in again' goes to the login page with the flag, and nothing else", () => {
    expect(SIGN_IN_AGAIN_PATH).toBe("/login?expired=1")
    expect(SESSION_EXPIRED_FLAG).toBe("expired")
  })

  it("recognises exactly expired=1", () => {
    expect(isSignInAgainRequest(params("expired=1"))).toBe(true)
    expect(isSignInAgainRequest(params("expired=1&from=%2Fdocuments"))).toBe(true)
    expect(isSignInAgainRequest(params(new URL(`http://x${SIGN_IN_AGAIN_PATH}`).search))).toBe(true)
  })

  it.each(["", "expired=0", "expired=", "expired=true", "expired=2", "expired=1x", "EXPIRED=1", "xexpired=1", "reason=expired", "from=%2Flogin%3Fexpired%3D1"])(
    "does not treat %j as a sign-in-again request",
    (query) => {
      expect(isSignInAgainRequest(params(query))).toBe(false)
    },
  )
})