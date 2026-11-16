import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

const state = vi.hoisted(() => ({
  pins: { pinOf: vi.fn(), canToggle: vi.fn(), openPin: vi.fn(), isLoading: false },
  granted: [] as string[],
}))

vi.mock("@/lib/pins/use-pins", () => ({ usePins: () => state.pins, PINS_KEY: ["pins"] }))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => state.granted.includes(code) }),
}))

import { PinButton } from "./pin-button"

describe("PinButton with the server's mark (search results)", () => {
  beforeEach(() => {
    state.pins.pinOf.mockReset().mockReturnValue(undefined)
    state.pins.canToggle.mockReset().mockReturnValue(true)
    state.pins.isLoading = false
    state.granted = ["docm.documents.view"]
  })

  it("is unchanged when no hint is given", () => {
    render(<PinButton row={{ documentId: "d1", title: "Plan" }} />)
    expect(screen.getByRole("button", { name: "Pin Plan" })).toHaveAttribute("aria-pressed", "false")
  })

  it("shows the server's mark while the shared pin list is still loading", () => {
    state.pins.isLoading = true
    render(<PinButton row={{ documentId: "d1", title: "Plan" }} pinned />)
    expect(screen.getByRole("button", { name: /Plan/ })).toHaveAttribute("aria-pressed", "true")
  })

  it("trusts the list once it has loaded, so unpinning shows at once", () => {
    render(<PinButton row={{ documentId: "d1", title: "Plan" }} pinned />)
    expect(screen.getByRole("button", { name: "Pin Plan" })).toHaveAttribute("aria-pressed", "false")
  })

  it("trusts the server's mark when the caller cannot read the list (no document view permission)", () => {
    state.granted = []
    render(<PinButton row={{ documentId: "d1", title: "Plan" }} pinned />)
    expect(screen.getByRole("button", { name: /Plan/ })).toHaveAttribute("aria-pressed", "true")
  })

  it("shows a plain pinned mark, and no button, for someone who cannot pin", () => {
    state.pins.canToggle.mockReturnValue(false)
    render(<PinButton row={{ documentId: "d1", title: "Plan" }} pinned />)
    expect(screen.getByRole("img", { name: "Pinned" })).toBeInTheDocument()
    expect(screen.queryByRole("button")).not.toBeInTheDocument()
  })
})
