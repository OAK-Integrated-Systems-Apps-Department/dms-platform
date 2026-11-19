import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import type { SearchAdminStatusDto } from "@/lib/admin/search-api"

const api = vi.hoisted(() => ({
  fetchSearchAdminStatus: vi.fn(),
  requestReindex: vi.fn(),
  requestReconcile: vi.fn(),
  fetchPresetLimit: vi.fn(),
  savePresetLimit: vi.fn(),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock("sonner", () => ({ toast }))
vi.mock("@/lib/admin/search-api", () => api)

import { SearchStatus } from "./search-status"

function status(patch: Partial<SearchAdminStatusDto> = {}): SearchAdminStatusDto {
  return {
    engine: "up",
    searchEnabled: true,
    indexReady: true,
    backfillState: "done",
    pendingJobs: 0,
    failedJobs: 0,
    lagSeconds: 0,
    generation: 3,
    lastReconciledAt: "2026-11-10T03:30:00Z",
    gap: { missing: 0, orphans: 0 },
    reindexRecommended: false,
    reindexRunning: false,
    reconcileRunning: false,
    ...patch,
  }
}

function renderIt() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SearchStatus />
    </QueryClientProvider>
  )
}

describe("Search index admin screen", () => {
  beforeEach(() => {
    for (const m of Object.values(api)) m.mockReset()
    toast.success.mockReset()
    toast.error.mockReset()
    api.fetchSearchAdminStatus.mockResolvedValue(status())
    api.fetchPresetLimit.mockResolvedValue({ maxPerUser: 50, defaultMax: 50 })
  })

  it("shows the engine, index, queue and last check when all is well", async () => {
    renderIt()
    expect(await screen.findByText("Running")).toBeInTheDocument()
    expect(screen.getByText("Index ready").nextSibling).toHaveTextContent("Yes")
    expect(screen.getByText("Complete")).toBeInTheDocument()
    expect(screen.getByText("None waiting")).toBeInTheDocument()
    expect(screen.getByText(/0 missing, 0 extra/)).toBeInTheDocument()
    expect(screen.queryByText(/Searchable metadata fields changed/)).not.toBeInTheDocument()
  })

  it("says the engine is down and that search uses the basic title search", async () => {
    api.fetchSearchAdminStatus.mockResolvedValue(status({ engine: "down", indexReady: false }))
    renderIt()
    expect(await screen.findByText(/Not reachable/)).toBeInTheDocument()
    expect(screen.getByText(/basic title search until it is ready/)).toBeInTheDocument()
  })

  it("shows failed jobs and the age of the oldest waiting change", async () => {
    api.fetchSearchAdminStatus.mockResolvedValue(status({ failedJobs: 4, pendingJobs: 12, lagSeconds: 125 }))
    renderIt()
    expect(await screen.findByText("4")).toBeInTheDocument()
    expect(screen.getByText("2 min 5 s")).toBeInTheDocument()
  })

  it("offers 'Run now' when the check has never run, and starts it", async () => {
    api.fetchSearchAdminStatus.mockResolvedValue(status({ lastReconciledAt: null, gap: null }))
    api.requestReconcile.mockResolvedValue(undefined)
    renderIt()
    expect(await screen.findByText("Never run")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Run now" }))
    await waitFor(() => expect(api.requestReconcile).toHaveBeenCalled())
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
  })

  it("shows the reindex hint when searchable metadata fields changed", async () => {
    api.fetchSearchAdminStatus.mockResolvedValue(status({ reindexRecommended: true }))
    renderIt()
    expect(await screen.findByText(/Searchable metadata fields changed since the last reindex/)).toBeInTheDocument()
  })

  it("asks for confirmation before reindexing, and does nothing if cancelled", async () => {
    api.requestReindex.mockResolvedValue({ generation: 4 })
    renderIt()
    fireEvent.click(await screen.findByRole("button", { name: "Reindex" }))
    expect(await screen.findByText(/re-reads every document of your organisation/)).toBeInTheDocument()
    expect(api.requestReindex).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))
    expect(api.requestReindex).not.toHaveBeenCalled()
  })

  it("starts the reindex after the confirmation", async () => {
    api.requestReindex.mockResolvedValue({ generation: 4 })
    renderIt()
    fireEvent.click(await screen.findByRole("button", { name: "Reindex" }))
    fireEvent.click(await screen.findByRole("button", { name: "Start reindex" }))
    await waitFor(() => expect(api.requestReindex).toHaveBeenCalled())
  })

  it("disables both actions while they run", async () => {
    api.fetchSearchAdminStatus.mockResolvedValue(status({ reindexRunning: true, reconcileRunning: true }))
    renderIt()
    expect(await screen.findByRole("button", { name: "Reindex running…" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "Check running…" })).toBeDisabled()
  })

  it("explains a refused reindex: already running (409)", async () => {
    api.requestReindex.mockRejectedValue(new ApiError(409, "busy", "REINDEX_IN_PROGRESS"))
    renderIt()
    fireEvent.click(await screen.findByRole("button", { name: "Reindex" }))
    fireEvent.click(await screen.findByRole("button", { name: "Start reindex" }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("A reindex is already running.", expect.anything()))
  })

  it("explains a refused check: too soon (429)", async () => {
    api.requestReconcile.mockRejectedValue(new ApiError(429, "slow", "RECONCILE_RATE_LIMITED"))
    renderIt()
    fireEvent.click(await screen.findByRole("button", { name: "Run check now" }))
    await waitFor(() => expect(toast.error).toHaveBeenCalled())
    expect(String(toast.error.mock.calls[0][0])).toMatch(/Wait a few minutes/)
  })

  describe("preset limit", () => {
    it("saves a new limit", async () => {
      api.savePresetLimit.mockResolvedValue({ maxPerUser: 80 })
      renderIt()
      const input = await screen.findByLabelText("Presets per person")
      expect(input).toHaveValue(50)
      fireEvent.change(input, { target: { value: "80" } })
      fireEvent.click(screen.getByRole("button", { name: "Save" }))
      await waitFor(() => expect(api.savePresetLimit).toHaveBeenCalledWith(80))
    })

    it("rejects a value outside 1 to 500 before asking the server", async () => {
      renderIt()
      const input = await screen.findByLabelText("Presets per person")
      fireEvent.change(input, { target: { value: "900" } })
      expect(screen.getByRole("button", { name: "Save" })).toBeDisabled()
      expect(screen.getByText("Enter a whole number from 1 to 500.")).toBeInTheDocument()
    })
  })
})
