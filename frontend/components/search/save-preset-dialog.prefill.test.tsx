import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import { EMPTY_FILTERS } from "@/lib/search/params"
import type { SearchFilters } from "@/lib/search/types"

const api = vi.hoisted(() => ({
  fetchPresets: vi.fn(),
  fetchPreset: vi.fn(),
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  runPreset: vi.fn(),
}))

vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() } }))
vi.mock("@/lib/search/presets-api", () => api)

import { SavePresetDialog } from "./save-preset-dialog"

const filters: SearchFilters = { ...EMPTY_FILTERS, q: "contract", department: "d1" }
const presets = [
  { presetId: "p1", name: "Renewals" },
  { presetId: "p2", name: "Other" },
]
// the same search as `filters`, as the server returns it: other key order, schema version, explicit defaults
const sameAsFilters = { schemaVersion: 1, departmentId: "d1", statuses: ["active"], q: "contract", sort: "relevance" }

function renderDialog(props: { activePresetId?: string | null; open?: boolean } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const onSaved = vi.fn()
  const ui = (open: boolean) => (
    <QueryClientProvider client={client}>
      <SavePresetDialog
        open={open}
        onOpenChange={vi.fn()}
        filters={filters}
        canOverwrite
        presets={presets}
        activePresetId={props.activePresetId ?? null}
        onSaved={onSaved}
        onManage={vi.fn()}
      />
    </QueryClientProvider>
  )
  const view = render(ui(props.open ?? true))
  return { onSaved, rerender: (open: boolean) => view.rerender(ui(open)) }
}

const field = () => screen.getByLabelText("Preset name") as HTMLInputElement
const type = (name: string) => fireEvent.change(field(), { target: { value: name } })

describe("Save as preset: the active preset's name", () => {
  beforeEach(() => {
    for (const m of Object.values(api)) m.mockReset()
    api.fetchPreset.mockResolvedValue({ presetId: "x", name: "x", criteria: { schemaVersion: 1, q: "something else" } })
  })

  it("is offered when the dialog opens", () => {
    renderDialog({ activePresetId: "p1" })
    expect(field().value).toBe("Renewals")
  })

  it("is offered again on every open, and a half-typed name is not kept across a close", () => {
    const { rerender } = renderDialog({ activePresetId: "p1", open: false })
    rerender(true)
    expect(field().value).toBe("Renewals")
    type("Something new")
    rerender(false)
    rerender(true)
    expect(field().value).toBe("Renewals")
  })

  it("leaves the field empty when no preset is active", () => {
    renderDialog({ activePresetId: null })
    expect(field().value).toBe("")
  })

  it("keeping the name goes to the existing replace confirmation", async () => {
    api.createPreset.mockRejectedValue(new ApiError(409, "exists", "PRESET_NAME_EXISTS", { presetId: "p1" }))
    renderDialog({ activePresetId: "p1" })
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    await waitFor(() => expect(screen.getByText("Replace this preset?")).toBeTruthy())
    expect(api.createPreset).toHaveBeenCalledWith(expect.objectContaining({ name: "Renewals", overwrite: false }))
  })

  it("typing a different name creates a new preset", async () => {
    api.createPreset.mockResolvedValue({ presetId: "p9", name: "Renewals 2" })
    const { onSaved } = renderDialog({ activePresetId: "p1" })
    type("Renewals 2")
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("p9", "Renewals 2"))
    expect(api.createPreset).toHaveBeenCalledWith(expect.objectContaining({ name: "Renewals 2", overwrite: false }))
  })
})

describe("Save as preset: already saved under another name", () => {
  beforeEach(() => {
    for (const m of Object.values(api)) m.mockReset()
    api.fetchPreset.mockImplementation(async (id: string) =>
      id === "p1"
        ? { presetId: "p1", name: "Renewals", criteria: sameAsFilters }
        : { presetId: "p2", name: "Other", criteria: { schemaVersion: 1, q: "different" } }
    )
  })

  it("shows a short note naming the preset, and Save stays enabled", async () => {
    renderDialog()
    type("A new name")
    expect(await screen.findByText("These settings are already saved as “Renewals”.")).toBeTruthy()
    expect((screen.getByRole("button", { name: "Save preset" }) as HTMLButtonElement).disabled).toBe(false)
  })

  it("still saves under the new name", async () => {
    api.createPreset.mockResolvedValue({ presetId: "p9", name: "A new name" })
    const { onSaved } = renderDialog()
    type("A new name")
    await screen.findByText(/already saved as/)
    fireEvent.click(screen.getByRole("button", { name: "Save preset" }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("p9", "A new name"))
  })

  it("does not call the preset itself a duplicate: same name (any case) shows no note", async () => {
    renderDialog({ activePresetId: "p1" })
    await waitFor(() => expect(api.fetchPreset).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/already saved as/)).toBeNull()
    type("renewals")
    expect(screen.queryByText(/already saved as/)).toBeNull()
  })

  it("shows no note when no preset has these settings", async () => {
    api.fetchPreset.mockResolvedValue({ presetId: "x", name: "x", criteria: { schemaVersion: 1, q: "different" } })
    renderDialog()
    type("A new name")
    await waitFor(() => expect(api.fetchPreset).toHaveBeenCalled())
    expect(screen.queryByText(/already saved as/)).toBeNull()
  })

  it("asks for the criteria only while the dialog is open", () => {
    renderDialog({ open: false })
    expect(api.fetchPreset).not.toHaveBeenCalled()
  })
})
