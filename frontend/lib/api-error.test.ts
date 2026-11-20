import { describe, expect, it } from "vitest"

import { ApiError, isNotFound } from "./api-error"

// FE-I1: "this resource does not exist" is decided by the HTTP status the server answered with, never by the words in the message.
describe("isNotFound", () => {
  it("is true for an ApiError with status 404 and the usual dms-core wording", () => {
    expect(isNotFound(new ApiError(404, "User not found: 7f1c"))).toBe(true)
  })

  it("is true for a 404 whatever its text says", () => {
    for (const message of ["There is nothing at this address.", "Gone", "Could not load this user.", "NOT FOUND", "something else entirely"]) {
      expect(isNotFound(new ApiError(404, message))).toBe(true)
    }
  })

  it("is true for a 404 with an empty message", () => {
    expect(isNotFound(new ApiError(404, ""))).toBe(true)
  })

  it("is true for a 404 that carries a code and details", () => {
    expect(isNotFound(new ApiError(404, "Nope", "SOME_CODE", { a: 1 }))).toBe(true)
  })

  it("is false for a 500 whose message says 'not found'", () => {
    expect(isNotFound(new ApiError(500, "User not found: 7f1c"))).toBe(false)
    expect(isNotFound(new ApiError(500, "Template not found in cache"))).toBe(false)
  })

  it("is false for a 403, even one whose message says 'not found'", () => {
    expect(isNotFound(new ApiError(403, "You don't have permission."))).toBe(false)
    expect(isNotFound(new ApiError(403, "Resource not found or not allowed"))).toBe(false)
  })

  it("is false for a 401 and for the other statuses", () => {
    for (const status of [400, 401, 405, 409, 410, 422, 429, 502, 503]) {
      expect(isNotFound(new ApiError(status, "not found"))).toBe(false)
    }
  })

  it("is false for a plain Error, however it is worded - only a status can say 404", () => {
    expect(isNotFound(new Error("not found"))).toBe(false)
    expect(isNotFound(new Error("User not found: 7f1c"))).toBe(false)
    expect(isNotFound(new TypeError("404"))).toBe(false)
  })

  it("is false for a network failure (status 0)", () => {
    expect(isNotFound(new ApiError(0, "Could not reach the server. Check your connection and try again."))).toBe(false)
  })

  it("is false for null, undefined and primitives", () => {
    for (const value of [null, undefined, 404, "404", "not found", true, 0, NaN]) {
      expect(isNotFound(value)).toBe(false)
    }
  })

  it("is false for objects that only look like an ApiError", () => {
    expect(isNotFound({ status: 404, message: "not found" })).toBe(false)
    expect(isNotFound({ name: "ApiError", status: 404 })).toBe(false)
    expect(isNotFound(Object.create(null))).toBe(false)
    expect(isNotFound({})).toBe(false)
    expect(isNotFound([404])).toBe(false)
  })

  it("is false for an Error that merely carries a status of 404 - it must be a genuine ApiError", () => {
    expect(isNotFound(Object.assign(new Error("x"), { status: 404 }))).toBe(false)
  })

  it("is true for a subclass of ApiError with status 404", () => {
    class Special extends ApiError {}
    expect(isNotFound(new Special(404, "x"))).toBe(true)
  })

  it("does not change what ApiError carries", () => {
    const error = new ApiError(404, "m", "C", [1])
    expect([error.status, error.message, error.code, error.details, error.name]).toEqual([404, "m", "C", [1], "ApiError"])
    expect(error).toBeInstanceOf(Error)
  })
})
