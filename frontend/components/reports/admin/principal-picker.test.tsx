import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { UserChips, UserSelect } from "./principal-picker"

const fetchJson = vi.fn()
vi.mock("@/lib/api-fetch", () => ({ apiFetchJson: (...a: unknown[]) => fetchJson(...a) }))
vi.mock("@/lib/departments/api", () => ({ fetchAllDepartments: vi.fn().mockResolvedValue([]) }))

function wrap(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

describe("people picker", () => {
  beforeEach(() => {
    fetchJson.mockReset().mockImplementation(async (url: string) =>
      url.includes("q=zed") ? [{ value: "u9", label: "Zed Zane" }] : [{ value: "u1", label: "Ann Lee" }])
  })

  it("searches on the server as the administrator types, not only in the first page", async () => {
    wrap(<UserSelect value="" onChange={vi.fn()} />)
    await waitFor(() => expect(fetchJson).toHaveBeenCalled())
    expect(String(fetchJson.mock.calls[0][0])).toContain("/api/v1/reports/admin/principals?type=user&limit=100&q=")
    fireEvent.change(screen.getByLabelText(/Search choose a person/i), { target: { value: "zed" } })
    await waitFor(() => expect(fetchJson.mock.calls.some((c) => String(c[0]).includes("q=zed"))).toBe(true))
  })

  it("keeps the name of a chosen person after the search moves on", async () => {
    wrap(<UserChips value={["u1"]} onChange={vi.fn()} />)
    expect(await screen.findByText("Ann Lee")).toBeInTheDocument()
  })
})
