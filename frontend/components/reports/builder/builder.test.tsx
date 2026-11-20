import { act, fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { PreviewDto } from "@/lib/reports/api"

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  startRun: vi.fn(),
  createTemplate: vi.fn(),
  catalog: undefined as unknown,
  template: undefined as unknown,
  preview: { data: undefined, isLoading: false, isFetching: false, isError: false, error: null, refetch: vi.fn() } as Record<string, unknown>,
  previewCalls: [] as { enabled: boolean }[],
}))

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock("@/components/admin/admin-breadcrumb", () => ({ AdminBreadcrumb: () => null }))
vi.mock("@/lib/reports/use-reports", () => ({
  useReportCatalog: () => ({ data: mocks.catalog, isLoading: false, isError: false, refetch: vi.fn() }),
  useTemplate: () => ({ data: mocks.template, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreateTemplate: () => ({ mutateAsync: mocks.createTemplate, isPending: false }),
  useUpdateTemplate: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useStartRun: () => ({ mutate: mocks.startRun, isPending: false }),
  useFieldValues: () => ({ data: { content: [], hasMore: false }, isLoading: false, isError: false, refetch: vi.fn() }),
  useReportPreview: (_definition: unknown, enabled: boolean) => {
    mocks.previewCalls.push({ enabled })
    return mocks.preview
  },
}))

import { PreviewPanel } from "./preview-panel"
import { ReportBuilder } from "./report-builder"
import { catalogFixture, definitionFixture, templateFixture } from "./fixtures"

const preview = (over: Partial<PreviewDto> = {}): PreviewDto => ({
  total: 2, columns: [{ key: "title", label: "Title", type: "text" }], sample: [["Alpha"], ["Beta"]], limitedToAccess: true,
  resolvedFilters: [], timedOut: false, timeZone: "UTC", maxRows: 100, maxPdfRows: 20, ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.catalog = catalogFixture()
  mocks.template = undefined
  mocks.previewCalls = []
  mocks.preview = { data: undefined, isLoading: false, isFetching: false, isError: false, error: null, refetch: vi.fn() }
})

describe("ReportBuilder", () => {
  it("starts a run for a valid report", () => {
    render(<ReportBuilder />)
    fireEvent.click(screen.getByRole("button", { name: /run now/i }))
    expect(mocks.startRun).toHaveBeenCalledTimes(1)
    expect(mocks.startRun.mock.calls[0][0]).toMatchObject({ format: "csv", definition: { columns: ["title"], filters: [] } })
  })

  it("does not run, and says why, while a filter has no value", () => {
    render(<ReportBuilder />)
    fireEvent.click(screen.getByRole("button", { name: /add a filter/i }))
    fireEvent.click(screen.getByRole("button", { name: "Title" }))
    fireEvent.click(screen.getByRole("button", { name: /run now/i }))
    expect(mocks.startRun).not.toHaveBeenCalled()
    expect(screen.getAllByRole("alert").map((a) => a.textContent).join(" ")).toContain("Give a value.")
  })

  it("does not save without a name, then saves and goes to the saved report", async () => {
    mocks.createTemplate.mockResolvedValue({ id: "new-id" })
    render(<ReportBuilder />)
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))
    expect(mocks.createTemplate).not.toHaveBeenCalled()
    expect(screen.getByText("Give the report a name.")).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Quarterly" } })
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /^save$/i }))
    })
    expect(mocks.createTemplate).toHaveBeenCalledWith({ name: "Quarterly", description: null, definition: expect.objectContaining({ columns: ["title"] }) })
    expect(mocks.push).toHaveBeenCalledWith("/reports/builder/new-id")
  })

  it("sends a saved report to the schedule dialog after saving", async () => {
    mocks.createTemplate.mockResolvedValue({ id: "t9" })
    render(<ReportBuilder />)
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Weekly" } })
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /save and schedule/i }))
    })
    expect(mocks.push).toHaveBeenCalledWith("/reports?tab=schedules&schedule=t9")
  })

  it("only asks for a preview while the definition is valid", () => {
    render(<ReportBuilder />)
    expect(mocks.previewCalls.at(-1)?.enabled).toBe(true)
    fireEvent.click(screen.getByRole("button", { name: /add a filter/i }))
    fireEvent.click(screen.getByRole("button", { name: "Title" }))
    expect(mocks.previewCalls.at(-1)?.enabled).toBe(false)
    expect(screen.getByText(/finish the highlighted parts/i)).toBeInTheDocument()
  })

  it("shows the fields a saved report lost as a warning", () => {
    mocks.template = templateFixture({ warnings: ["Owner is no longer available"] })
    render(<ReportBuilder templateId="t1" />)
    expect(screen.getByText("Owner is no longer available")).toBeInTheDocument()
  })

  it("opens a locked official report the person does not own in the reduced run view", () => {
    mocks.template = templateFixture({
      scope: "official", locked: true, access: "run", ownerName: "Admin",
      definition: definitionFixture({ filters: [{ field: "title", operator: "contains", values: ["a"] }, { field: "pageCount", operator: "gt", values: ["1"] }] }),
      userEditableFilters: ["title"],
    })
    render(<ReportBuilder templateId="t1" />)
    expect(screen.getByText("Run with options")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument()
    expect(screen.queryByText("Build a report")).not.toBeInTheDocument()
    expect(screen.getByLabelText("Title value")).toBeEnabled()
    expect(screen.getByLabelText("Page count value")).toBeDisabled()

    fireEvent.change(screen.getByLabelText("Title value"), { target: { value: "beta" } })
    fireEvent.click(screen.getByRole("button", { name: /run report/i }))
    expect(mocks.startRun.mock.calls[0][0]).toEqual({
      templateId: "t1", format: "csv", filterOverrides: [{ field: "title", operator: "contains", values: ["beta"] }],
    })
  })

  it("gives the owner of a locked report the full builder", () => {
    mocks.template = templateFixture({ locked: true, access: "owner" })
    render(<ReportBuilder templateId="t1" />)
    expect(screen.getByText("Edit report")).toBeInTheDocument()
  })

  it("gives someone who cannot build the reduced view", () => {
    mocks.catalog = catalogFixture({ build: false })
    mocks.template = templateFixture({ access: "edit" })
    render(<ReportBuilder templateId="t1" />)
    expect(screen.getByText("Run with options")).toBeInTheDocument()
  })
})

describe("PreviewPanel", () => {
  const props = { definition: definitionFixture(), catalog: catalogFixture(), format: "csv", valid: true, labels: {} }

  it("shows a skeleton while loading", () => {
    mocks.preview = { ...mocks.preview, isLoading: true }
    render(<PreviewPanel {...props} />)
    expect(screen.getByRole("status", { name: /loading preview/i })).toBeInTheDocument()
  })

  it("shows the sentence, the total, the rows and the access notice", () => {
    mocks.preview = { ...mocks.preview, data: preview() }
    render(<PreviewPanel {...props} />)
    expect(screen.getByTestId("report-sentence")).toHaveTextContent(/All documents you can see/)
    expect(screen.getByTestId("report-total")).toHaveTextContent("2 documents match")
    expect(screen.getByText("Alpha")).toBeInTheDocument()
    expect(screen.getByText(/limited to the documents you have access to/i)).toBeInTheDocument()
  })

  it("says so when nothing matches", () => {
    mocks.preview = { ...mocks.preview, data: preview({ total: 0, sample: [] }) }
    render(<PreviewPanel {...props} />)
    expect(screen.getByText("No data found for these filters.")).toBeInTheDocument()
  })

  it("shows an error with a retry that asks again", () => {
    const refetch = vi.fn()
    mocks.preview = { ...mocks.preview, isError: true, error: new Error("boom"), refetch }
    render(<PreviewPanel {...props} />)
    fireEvent.click(screen.getByRole("button", { name: "Retry" }))
    expect(refetch).toHaveBeenCalled()
  })

  it("says a report that timed out is too large to count quickly", () => {
    mocks.preview = { ...mocks.preview, data: preview({ total: null, timedOut: true }) }
    render(<PreviewPanel {...props} />)
    expect(screen.getByTestId("report-total")).toHaveTextContent("Too large to count quickly")
  })

  it("warns, and reports the format as too small, when the report is over its limit", () => {
    const onTooLarge = vi.fn()
    mocks.preview = { ...mocks.preview, data: preview({ total: 50 }) }
    render(<PreviewPanel {...props} format="pdf" onTooLarge={onTooLarge} />)
    expect(screen.getByRole("alert")).toHaveTextContent(/at most 20 rows/)
    expect(onTooLarge).toHaveBeenLastCalledWith(true)
  })

  it("does not ask for a preview, and says what is missing, while the definition is invalid", () => {
    render(<PreviewPanel {...props} valid={false} />)
    expect(mocks.previewCalls.at(-1)?.enabled).toBe(false)
    expect(screen.getByText(/finish the highlighted parts/i)).toBeInTheDocument()
  })
})
