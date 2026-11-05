import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { EditingSettingsDto } from "@/lib/checkout/api"

import { EditingSettingsCard } from "./editing-settings-card"

let settings: EditingSettingsDto | undefined
const mutate = vi.fn()

vi.mock("@/lib/checkout/use-checkout", () => ({
  useEditingSettings: () => ({ data: settings, isPending: false, isError: false }),
  useSaveEditingSettings: () => ({ mutate, isPending: false }),
}))

const base: EditingSettingsDto = { enabled: false, mode: "exclusive", engineConfigured: true, allowedByPlatform: true }

describe("EditingSettingsCard", () => {
  beforeEach(() => {
    mutate.mockClear()
    settings = base
  })

  it("shows the organisation's current setting and offers no save until something changes", () => {
    settings = { ...base, enabled: true, mode: "simultaneous" }
    render(<EditingSettingsCard canEdit />)

    expect(screen.getByRole("switch", { name: "Online editing" })).toBeChecked()
    expect(screen.getByRole("radio", { name: "Together" })).toHaveAttribute("aria-checked", "true")
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled()
  })

  it("turning it on and choosing a mode saves exactly that", () => {
    render(<EditingSettingsCard canEdit />)

    fireEvent.click(screen.getByRole("switch", { name: "Online editing" }))
    fireEvent.click(screen.getByRole("radio", { name: "Together" }))
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    expect(mutate).toHaveBeenCalledWith({ enabled: true, mode: "simultaneous" })
  })

  it("cannot be turned on when the server has no engine - and says why", () => {
    settings = { ...base, engineConfigured: false }
    render(<EditingSettingsCard canEdit />)

    expect(screen.getByRole("switch", { name: "Online editing" })).toHaveAttribute("aria-disabled", "true")
    expect(screen.getByText(/no editing engine set up/i)).toBeInTheDocument()
  })

  it("can still be turned OFF when the server has lost its engine", () => {
    settings = { ...base, enabled: true, engineConfigured: false }
    render(<EditingSettingsCard canEdit />)

    const toggle = screen.getByRole("switch", { name: "Online editing" })
    expect(toggle).not.toHaveAttribute("aria-disabled", "true")
    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    expect(mutate).toHaveBeenCalledWith({ enabled: false, mode: "exclusive" })
  })

  it("is read-only without the select-provider permission", () => {
    render(<EditingSettingsCard canEdit={false} />)

    expect(screen.getByRole("switch", { name: "Online editing" })).toHaveAttribute("aria-disabled", "true")
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled()
    expect(screen.getByText(/see this setting but not change it/i)).toBeInTheDocument()
  })

  it("shows nothing to switch - only why - while the platform administrator has not allowed it", () => {
    settings = { ...base, enabled: true, allowedByPlatform: false }
    render(<EditingSettingsCard canEdit />)

    expect(screen.getByText(/isn.t available to your organisation/)).toBeInTheDocument()
    expect(screen.queryByRole("switch", { name: "Online editing" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull()
  })

  it("shows the switch and the modes again once it is allowed", () => {
    settings = { ...base, allowedByPlatform: true }
    render(<EditingSettingsCard canEdit />)

    expect(screen.getByRole("switch", { name: "Online editing" })).toBeInTheDocument()
    expect(screen.queryByText(/isn.t available to your organisation/)).toBeNull()
  })
})
