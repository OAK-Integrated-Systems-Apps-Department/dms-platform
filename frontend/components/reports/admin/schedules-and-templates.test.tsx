import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { toast } from "sonner"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import type { AdminScheduleDto, TemplateDto } from "@/lib/reports/api"

import { AdminSchedules } from "./admin-schedules"
import { OfficialTemplatesTab } from "./official-templates-tab"

const reassign = vi.fn()
const act = vi.fn()
let schedules: AdminScheduleDto[]
let templates: TemplateDto[]
let granted: string[]

vi.mock("@/lib/reports/use-reports", () => ({
  useAdminSchedules: () => ({
    data: { content: schedules, page: 0, size: 20, totalElements: schedules.length, totalPages: 1, last: true },
    isPending: false, isError: false,
  }),
  useAdminScheduleAction: () => ({ mutateAsync: act, isPending: false }),
  useAdminReassign: () => ({ mutateAsync: reassign, isPending: false }),
  useTemplates: () => ({ data: templates, isPending: false, isError: false }),
  usePublishTemplate: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUnpublishTemplate: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useReportCatalog: () => ({ data: { fields: [] } }),
}))
vi.mock("@/lib/auth/use-permissions", () => ({ usePermissions: () => ({ hasPermission: (c: string) => granted.includes(c) }) }))
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }))
vi.mock("./principal-picker", () => ({
  UserSelect: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <input aria-label="New owner" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
  GrantPicker: () => <div>audience</div>,
}))

const schedule = (over: Partial<AdminScheduleDto> = {}): AdminScheduleDto => ({
  id: "s1", name: "Weekly contracts", ownerUserId: "u1", ownerName: "Ana", templateId: "t1", templateName: "Contracts",
  frequency: "weekly", timeZone: "Asia/Colombo", format: "csv", status: "active", pauseReason: null, nextRunAt: null, lastRunAt: null,
  consecutiveFailures: 0, lastFailureCode: null, recipientCount: 2, ...over,
})

const template = (over: Partial<TemplateDto> = {}): TemplateDto => ({
  id: "t1", name: "Contracts", description: null, scope: "private", ownerUserId: "me", ownerName: "Me",
  definition: { source: "documents", filters: [], columns: ["title"], sort: [], groupBy: null, includeInactive: false, timeZone: null },
  locked: false, userEditableFilters: null, access: "owner", warnings: null, grants: null, activeSchedules: 0,
  createdAt: "2026-01-01T00:00:00Z", updatedAt: null, ...over,
})

describe("AdminSchedules", () => {
  beforeEach(() => {
    reassign.mockReset()
    act.mockReset()
    act.mockResolvedValue(undefined)
    vi.mocked(toast.error).mockClear()
    schedules = [schedule(), schedule({ id: "s2", name: "Paused one", status: "paused", pauseReason: "OWNER_INACTIVE" })]
  })

  it("lists every schedule with its owner and a recipient count, never the recipients", () => {
    render(<AdminSchedules />)
    expect(screen.getByText("Weekly contracts")).toBeInTheDocument()
    expect(screen.getAllByText("Ana")).toHaveLength(2)
    expect(screen.getAllByText("2")).toHaveLength(2)
  })

  it("pauses only after confirming", async () => {
    render(<AdminSchedules />)
    fireEvent.click(screen.getByRole("button", { name: "Pause" }))
    expect(act).not.toHaveBeenCalled()
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Pause" }))
    await waitFor(() => expect(act).toHaveBeenCalledWith({ id: "s1", action: "pause" }))
  })

  it("says why a hand-over was refused and keeps the dialog open", async () => {
    reassign.mockRejectedValue(new ApiError(422, "x", "REASSIGN_OWNER_CANNOT_RUN"))
    render(<AdminSchedules />)
    fireEvent.click(screen.getAllByRole("button", { name: "Reassign" })[0])
    fireEvent.change(await screen.findByLabelText("New owner"), { target: { value: "u2" } })
    fireEvent.click(screen.getByRole("button", { name: "Hand over" }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("That person is not allowed to run these reports.", expect.anything()))
    expect(reassign).toHaveBeenCalledWith({ id: "s1", newOwnerUserId: "u2" })
    expect(screen.getByLabelText("New owner")).toBeInTheDocument()
  })

  it("will not hand over until someone is chosen", async () => {
    render(<AdminSchedules />)
    fireEvent.click(screen.getAllByRole("button", { name: "Reassign" })[0])
    expect(await screen.findByRole("button", { name: "Hand over" })).toBeDisabled()
  })
})

describe("OfficialTemplatesTab", () => {
  beforeEach(() => {
    templates = [template({ id: "o1", name: "Official contracts", scope: "official", locked: true, access: "run" }), template()]
  })

  it("offers publishing and the builder only to those who hold the permissions", () => {
    granted = []
    const { unmount } = render(<OfficialTemplatesTab />)
    expect(screen.getByText("Official contracts")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Unpublish" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Publish" })).not.toBeInTheDocument()
    expect(screen.queryByText("Your reports")).not.toBeInTheDocument()
    unmount()

    granted = ["crg.manage-report-templates.publish", "crg.generate-custom-reports.build"]
    render(<OfficialTemplatesTab />)
    expect(screen.getByRole("button", { name: "Unpublish" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Publish" })).toBeInTheDocument()
    expect(screen.getAllByText("Edit in builder").length).toBeGreaterThan(0)
  })
})
