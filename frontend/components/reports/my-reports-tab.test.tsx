import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { MyReportsTab } from "./my-reports-tab"
import { catalogFixture, templateFixture } from "./test-fixtures"

let templates = [templateFixture()]
let catalog = catalogFixture()
const duplicate = vi.fn()

vi.mock("next/link", () => ({ default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }))
vi.mock("@tanstack/react-query", () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }))
vi.mock("@/lib/reports/use-reports", () => ({
  reportKeys: { templates: ["t"] },
  useTemplates: () => ({ data: templates, isPending: false, isError: false }),
  useReportCatalog: () => ({ data: catalog, isPending: false, isError: false }),
  useDuplicateTemplate: () => ({ mutate: duplicate, isPending: false }),
  useStartRun: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useRecipientDirectory: () => ({ users: [], roles: [], departments: [], loading: false }),
  useShareTemplate: () => ({ mutate: vi.fn(), isPending: false }),
  useCreateSchedule: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateSchedule: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useFieldValues: () => ({ data: undefined, isPending: true }),
}))
vi.mock("@/lib/reports/api", () => ({ deleteTemplate: vi.fn() }))

describe("MyReportsTab", () => {
  beforeEach(() => {
    templates = [templateFixture()]
    catalog = catalogFixture()
    duplicate.mockClear()
  })

  it("shows the owner everything they may do", () => {
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    expect(screen.getByRole("link", { name: "Edit" })).toHaveAttribute("href", "/reports/builder/t1")
    for (const name of ["Run", "Duplicate", "Schedule", "Share", "Delete"]) {
      expect(screen.getByRole("button", { name })).toBeInTheDocument()
    }
    expect(screen.getByRole("link", { name: "New report" })).toHaveAttribute("href", "/reports/builder")
  })

  it("gives someone who can only run a shared report just Run", () => {
    templates = [templateFixture({ scope: "shared", access: "run", ownerName: "Ben" })]
    catalog = catalogFixture({ capabilities: { build: false, schedule: false } })
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    expect(screen.getByRole("button", { name: "Run" })).toBeInTheDocument()
    for (const name of ["Duplicate", "Schedule", "Share", "Delete"]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "New report" })).not.toBeInTheDocument()
  })

  it("hides Schedule when the organisation has switched scheduling off", () => {
    catalog = catalogFixture({ settings: { schedulingEnabled: false } })
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    expect(screen.queryByRole("button", { name: "Schedule" })).not.toBeInTheDocument()
  })

  it("does not offer Edit on a locked official report", () => {
    templates = [templateFixture({ scope: "official", locked: true, access: "run" })]
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument()
    expect(screen.getByText("Official")).toBeInTheDocument()
  })

  it("duplicates, and shows warnings and an empty state", () => {
    templates = [templateFixture({ warnings: ["A column is no longer available."] })]
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    expect(screen.getByText("A column is no longer available.")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Duplicate" }))
    expect(duplicate).toHaveBeenCalledWith("t1")
  })

  it("says so when there are no reports", () => {
    templates = []
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    expect(screen.getByText("You have no saved reports yet.")).toBeInTheDocument()
  })

  it("opens the run dialog", () => {
    render(<MyReportsTab onViewHistory={vi.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: "Run" }))
    expect(screen.getByRole("button", { name: "Run report" })).toBeInTheDocument()
  })
})
