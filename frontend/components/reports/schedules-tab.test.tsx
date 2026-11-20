import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { ScheduleDto } from "@/lib/reports/api"

import { SchedulesTab } from "./schedules-tab"
import { catalogFixture, scheduleFixture } from "./test-fixtures"

let schedules: ScheduleDto[] = []
const pause = vi.fn()
const resume = vi.fn()
const runNow = vi.fn()

vi.mock("@tanstack/react-query", () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }))
vi.mock("@/lib/reports/api", () => ({ cancelSchedule: vi.fn() }))
vi.mock("@/lib/reports/use-reports", () => ({
  reportKeys: { schedules: ["s"], templates: ["t"] },
  useSchedules: () => ({ data: schedules, isPending: false, isError: false }),
  useReportCatalog: () => ({ data: catalogFixture(), isPending: false, isError: false }),
  usePauseSchedule: () => ({ mutate: pause, isPending: false }),
  useResumeSchedule: () => ({ mutate: resume, isPending: false }),
  useRunScheduleNow: () => ({ mutate: runNow, isPending: false }),
  useCreateSchedule: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateSchedule: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useRecipientDirectory: () => ({ users: [], roles: [], departments: [], loading: false }),
}))

describe("SchedulesTab", () => {
  beforeEach(() => {
    schedules = []
    pause.mockClear()
    resume.mockClear()
    runNow.mockClear()
  })

  it("pauses and runs an active schedule", () => {
    schedules = [scheduleFixture()]
    render(<SchedulesTab />)
    expect(screen.getByText("Weekly contracts")).toBeInTheDocument()
    expect(screen.getByText("Every Mon at 08:00")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Pause" }))
    expect(pause).toHaveBeenCalledWith("s1")
    fireEvent.click(screen.getByRole("button", { name: "Run now" }))
    expect(runNow).toHaveBeenCalledWith("s1")
    expect(screen.queryByRole("button", { name: "Resume" })).not.toBeInTheDocument()
  })

  it("resumes a paused schedule and says why it is paused", () => {
    schedules = [scheduleFixture({ status: "paused", pauseReason: "OWNER_INACTIVE", nextRunAt: null })]
    render(<SchedulesTab />)
    expect(screen.getByText(/no longer an active user/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Resume" }))
    expect(resume).toHaveBeenCalledWith("s1")
    expect(screen.queryByRole("button", { name: "Pause" })).not.toBeInTheDocument()
  })

  it("shows the last failure", () => {
    schedules = [scheduleFixture({ lastFailureCode: "FIELDS_UNAVAILABLE", consecutiveFailures: 2 })]
    render(<SchedulesTab />)
    expect(screen.getByText(/FIELDS_UNAVAILABLE/)).toHaveTextContent("2 in a row")
  })

  it("asks before cancelling", () => {
    schedules = [scheduleFixture()]
    render(<SchedulesTab />)
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))
    expect(screen.getByText("Cancel this schedule?")).toBeInTheDocument()
  })

  it("hides cancelled schedules and says when there are none", () => {
    schedules = [scheduleFixture({ status: "cancelled" })]
    render(<SchedulesTab />)
    expect(screen.getByText("You have no schedules.")).toBeInTheDocument()
  })
})
