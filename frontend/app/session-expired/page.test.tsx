import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import SessionExpiredPage from "./page"

const mocks = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: mocks.push }) }))

beforeEach(() => vi.clearAllMocks())

describe("the session-expired page", () => {
  it("'Sign in again' goes to /login?expired=1, so proxy.ts shows the form even if a stale access cookie remains", () => {
    render(<SessionExpiredPage />)

    fireEvent.click(screen.getByRole("button", { name: /sign in again/i }))

    expect(mocks.push).toHaveBeenCalledTimes(1)
    expect(mocks.push).toHaveBeenCalledWith("/login?expired=1")
  })

  it("does not navigate until the button is pressed", () => {
    render(<SessionExpiredPage />)

    expect(mocks.push).not.toHaveBeenCalled()
  })
})