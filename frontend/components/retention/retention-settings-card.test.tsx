import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RetentionSettingsDto } from "@/lib/retention/api"

import { RetentionSettingsCard } from "./retention-settings-card"

let settings: RetentionSettingsDto
let granted: string[] = []
const save = vi.fn()
const run = vi.fn()

vi.mock("@/lib/retention/use-retention", () => ({
  useRetentionSettings: () => ({ data: settings, isPending: false, isError: false }),
  useSaveRetentionSettings: () => ({ mutate: save, isPending: false }),
  useRunRetention: () => ({ mutate: run, isPending: false }),
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

const CONFIGURE = "crm.configure-retention"
const DEFAULTS: RetentionSettingsDto = {
  automationEnabled: true,
  precedence: "longest",
  whenNoPolicy: "flag",
  requireStatusReason: false,
  versionGraceDays: 30,
}

describe("RetentionSettingsCard", () => {
  beforeEach(() => {
    save.mockClear()
    run.mockClear()
    settings = DEFAULTS
    granted = []
  })

  it("shows the organisation's settings and offers no save until something changes", () => {
    granted = [CONFIGURE]
    render(<RetentionSettingsCard />)
    expect(screen.getByRole("switch", { name: "Run retention automatically" })).toBeChecked()
    expect(screen.getByRole("radio", { name: "Longest retention" })).toHaveAttribute("aria-checked", "true")
    expect(screen.getByRole("radio", { name: "Flag it for an administrator" })).toHaveAttribute("aria-checked", "true")
    expect(screen.getByLabelText("Days a removed version stays restorable")).toHaveValue(30)
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled()
  })

  it("saves exactly what was changed - every setting, including how long removed versions stay restorable", () => {
    granted = [CONFIGURE]
    render(<RetentionSettingsCard />)
    fireEvent.click(screen.getByRole("switch", { name: "Run retention automatically" }))
    fireEvent.click(screen.getByRole("radio", { name: "Most specific rule" }))
    fireEvent.click(screen.getByRole("radio", { name: "Leave it alone" }))
    fireEvent.click(screen.getByRole("switch", { name: "Require a reason for status changes" }))
    fireEvent.change(screen.getByLabelText("Days a removed version stays restorable"), { target: { value: "90" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    expect(save).toHaveBeenCalledWith({
      automationEnabled: false,
      precedence: "most_specific",
      whenNoPolicy: "ignore",
      requireStatusReason: true,
      versionGraceDays: 90,
    })
  })

  it("someone without the configure permission can look but not change or run anything", () => {
    render(<RetentionSettingsCard />)
    expect(screen.getByRole("switch", { name: "Run retention automatically" })).toHaveAttribute("aria-disabled", "true")
    expect(screen.getByLabelText("Days a removed version stays restorable")).toBeDisabled()
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled()
    expect(screen.queryByRole("button", { name: "Apply rules now" })).not.toBeInTheDocument()
    expect(screen.getByText(/needs the "configure retention" permission/)).toBeInTheDocument()
  })

  it("'Apply rules now' runs retention - but not while there are unsaved changes", () => {
    granted = [CONFIGURE]
    render(<RetentionSettingsCard />)
    const apply = screen.getByRole("button", { name: "Apply rules now" })
    expect(apply).toBeEnabled()
    fireEvent.click(apply)
    expect(run).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole("radio", { name: "Most specific rule" }))
    expect(screen.getByRole("button", { name: "Apply rules now" })).toBeDisabled()
  })
})
