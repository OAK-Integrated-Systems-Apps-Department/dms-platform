import { describe, expect, it } from "vitest"

import { ApiError, friendlyMessage } from "@/lib/api-error"

import { confirmationHeaders } from "./password-confirmation"

describe("confirmationHeaders", () => {
  it("carries the password in a header, so DELETE works like POST and PUT", () => {
    expect(confirmationHeaders({ password: "my own password" })).toEqual({
      "X-Confirm-Password": "my own password",
    })
  })

  it("adds the authenticator code only when there is one", () => {
    expect(confirmationHeaders({ password: "p", code: " 123456 " })).toEqual({
      "X-Confirm-Password": "p",
      "X-Confirm-Totp": "123456",
    })
    expect(confirmationHeaders({ password: "p", code: "  " })).toEqual({
      "X-Confirm-Password": "p",
    })
  })

  it("leaves the password exactly as typed - spaces and all", () => {
    expect(confirmationHeaders({ password: "  spaced  " })["X-Confirm-Password"]).toBe("  spaced  ")
  })
})

describe("friendlyMessage for a refused password confirmation", () => {
  it("says what the server says - a wrong password is not a missing permission", () => {
    const wrong = new ApiError(403, "That password isn't right.", "PASSWORD_INCORRECT")
    expect(friendlyMessage(wrong)).toBe("That password isn't right.")
    expect(
      friendlyMessage(new ApiError(403, "Enter your password to confirm this.", "PASSWORD_REQUIRED"))
    ).toBe("Enter your password to confirm this.")
  })

  it("still words an ordinary 403 as a missing permission", () => {
    expect(friendlyMessage(new ApiError(403, "Forbidden", "ACCESS_DENIED"))).toBe(
      "You don't have permission to do that."
    )
  })
})
