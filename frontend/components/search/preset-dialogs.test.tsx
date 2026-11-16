import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import { EMPTY_FILTERS } from "@/lib/search/params"
import type { SearchFilters } from "@/lib/search/types"

const api = vi.hoisted(() => ({
  fetchPresets: vi.fn(),
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  runPreset: vi.fn(),
}))
let granted: string[] = []

vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() } }))
vi.mock("@/lib/search/presets-api", () => api)
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

import { ManagePresetsDialog } from "./manage-presets-dialog"
import { SavePresetDialog } from "./save-preset-dialog"

const ALL = [
  "dsr.search-documents",
  "dsr.manage-saved-search-presets.create",
  "dsr.manage-saved-search-presets.update",
  "dsr.manage-saved-search-presets.delete",
]
const withCriteria: SearchFilters = { ...EMPTY_FILTERS, q: "contract", department: "d1" }

function wrap(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

function renderSave(filters: SearchFilters, extra: { canOverwrite?: boolean } = {}) {
  const onSaved = vi.fn()
  const onManage = vi.fn()
  const onOpenChange = vi.fn()
  wrap(
    <SavePresetDialog
      open
      onOpenChange={onOpenChange}
      filters={filters}
      canOverwrite={extra.canOverwrite ?? true}
      onSaved={onSaved}
      onManage={onManage}
    />
  )
  return { onSaved, onManage, onOpenChange }
}

const type = (name: string) => fireEvent.change(screen.getByLabelText("Preset name"), { target: { value: name } })

describe("Save as preset", () => {
  beforeEach(() => {
    for (const m of Object.values(api)) m.mockReset()
    granted = ALL
  })

  it("saves the current keyword and filters under the name and tells the page", async () => {
    api.createPreset.mockResolvedValue({ presetId: "p9", name: "Renewals" })
    const { onSaved } = renderSave(withCriteria)
    type("  Renewals ")
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("p9", "Renewals"))
    expect(api.createPreset).toHaveBeenCalledWith({
      name: "Renewals",
      criteria: { schemaVersion: 1, q: "contract", departmentId: "d1" },
      overwrite: false,
    })
  })

  it("will not save without a keyword or filter, and says why", () => {
    renderSave(EMPTY_FILTERS)
    type("Anything")
    expect(screen.getByRole("button", { name: "Save preset" })).toBeDisabled()
    expect(screen.getByText(/Add a keyword or a filter first/)).toBeInTheDocument()
  })

  it("asks before replacing a preset with the same name, then replaces it", async () => {
    api.createPreset
      .mockRejectedValueOnce(new ApiError(409, "exists", "PRESET_NAME_EXISTS", { presetId: "p1" }))
      .mockResolvedValueOnce({ presetId: "p1", name: "Renewals" })
    const { onSaved } = renderSave(withCriteria)
    type("Renewals")
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    expect(await screen.findByText(/A preset named “Renewals” already exists/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Replace" }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(api.createPreset).toHaveBeenLastCalledWith(expect.objectContaining({ name: "Renewals", overwrite: true }))
  })

  it("does not offer to replace when the person may not change presets", async () => {
    api.createPreset.mockRejectedValue(new ApiError(409, "exists", "PRESET_NAME_EXISTS", { presetId: "p1" }))
    renderSave(withCriteria, { canOverwrite: false })
    type("Renewals")
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    expect(await screen.findByText(/already have a preset named “Renewals”/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Replace" })).not.toBeInTheDocument()
  })

  it("explains the limit and offers a way to make room", async () => {
    api.createPreset.mockRejectedValue(new ApiError(409, "limit", "PRESET_LIMIT_REACHED", { limit: 50 }))
    const { onManage } = renderSave(withCriteria)
    type("One more")
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    expect(await screen.findByText(/reached the limit of 50 presets/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Manage presets" }))
    expect(onManage).toHaveBeenCalled()
  })

  it("passes on the server's own refusal when there is nothing to search for", async () => {
    api.createPreset.mockRejectedValue(new ApiError(400, "none", "PRESET_NO_CRITERIA"))
    renderSave(withCriteria)
    type("X")
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    expect(await screen.findByRole("alert")).toHaveTextContent(/Add a keyword or a filter first/)
  })
})

describe("Manage presets", () => {
  const list = {
    items: [
      { presetId: "p1", name: "Renewals", summary: "contract", updatedAt: "2026-02-01T00:00:00Z", runnable: true },
      { presetId: "p2", name: "Old format", summary: "?", updatedAt: "2026-02-01T00:00:00Z", runnable: false },
    ],
    count: 2,
    limit: 50,
  }
  beforeEach(() => {
    for (const m of Object.values(api)) m.mockReset()
    api.fetchPresets.mockResolvedValue(list)
    granted = ALL
  })
  const open = (filters: SearchFilters = withCriteria) => wrap(<ManagePresetsDialog open onOpenChange={vi.fn()} filters={filters} />)

  it("shows the count against the limit and flags a preset saved by a newer version", async () => {
    open()
    expect(await screen.findByText("2 of 50 presets used")).toBeInTheDocument()
    expect(screen.getByText(/saved by a newer version and cannot be run/)).toBeInTheDocument()
  })

  it("renames a preset, and says so when the name is taken", async () => {
    api.updatePreset
      .mockRejectedValueOnce(new ApiError(409, "dup", "PRESET_NAME_EXISTS"))
      .mockResolvedValueOnce({ presetId: "p1", name: "Renewals 2" })
    open()
    fireEvent.click(await screen.findByRole("button", { name: "Rename Renewals" }))
    const input = screen.getByLabelText("New name for Renewals")
    fireEvent.change(input, { target: { value: "Old format" } })
    fireEvent.click(screen.getByRole("button", { name: "Save name" }))
    expect(await screen.findByText("You already have a preset with that name.")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("New name for Renewals"), { target: { value: "Renewals 2" } })
    fireEvent.click(screen.getByRole("button", { name: "Save name" }))
    await waitFor(() => expect(api.updatePreset).toHaveBeenLastCalledWith("p1", { name: "Renewals 2" }))
  })

  it("points a preset at the search on screen", async () => {
    api.updatePreset.mockResolvedValue({ presetId: "p1", name: "Renewals" })
    open()
    fireEvent.click(await screen.findByRole("button", { name: "Update Renewals to the current search" }))
    await waitFor(() =>
      expect(api.updatePreset).toHaveBeenCalledWith("p1", {
        criteria: { schemaVersion: 1, q: "contract", departmentId: "d1" },
      })
    )
  })

  it("will not point a preset at an empty search", async () => {
    open(EMPTY_FILTERS)
    expect(await screen.findByRole("button", { name: "Update Renewals to the current search" })).toBeDisabled()
  })

  it("deletes only after a confirmation", async () => {
    api.deletePreset.mockResolvedValue(undefined)
    open()
    fireEvent.click(await screen.findByRole("button", { name: "Delete Renewals" }))
    expect(api.deletePreset).not.toHaveBeenCalled()
    const dialog = await screen.findByText(/will be removed/)
    expect(dialog).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Delete preset" }))
    await waitFor(() => expect(api.deletePreset).toHaveBeenCalledWith("p1"))
  })

  it("disables rename, update and delete (with the reason) without the manage permissions", async () => {
    granted = ["dsr.search-documents"]
    open()
    const rename = await screen.findByRole("button", { name: "Rename Renewals" })
    expect(rename).toBeDisabled()
    expect(rename).toHaveAttribute("title", expect.stringMatching(/permission/))
    expect(screen.getByRole("button", { name: "Delete Renewals" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "Update Renewals to the current search" })).toBeDisabled()
  })

  it("lists an empty state", async () => {
    api.fetchPresets.mockResolvedValue({ items: [], count: 0, limit: 50 })
    open()
    expect(await screen.findByText("You have no saved presets yet.")).toBeInTheDocument()
    expect(within(document.body).queryByRole("button", { name: /Rename/ })).not.toBeInTheDocument()
  })
})
