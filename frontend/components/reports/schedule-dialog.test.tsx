import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"

import { ScheduleDialog } from "./schedule-dialog"
import { catalogFixture, scheduleFixture } from "./test-fixtures"

// The dialog is a large form; under a loaded full-suite run a render can pass the default 5 s.
vi.setConfig({ testTimeout: 30_000 })

const create = vi.fn()
const update = vi.fn()
vi.mock("@/lib/reports/use-reports", () => ({
  useCreateSchedule: () => ({ mutateAsync: create, isPending: false }),
  useUpdateSchedule: () => ({ mutateAsync: update, isPending: false }),
  useRecipientDirectory: () => ({ users: [], roles: [], departments: [], loading: false }),
}))

const TEMPLATE = { id: "t1", name: "Contracts" }

function open(catalog = catalogFixture(), schedule?: ReturnType<typeof scheduleFixture>) {
  const onClose = vi.fn()
  render(<ScheduleDialog catalog={catalog} template={TEMPLATE} schedule={schedule} onClose={onClose} />)
  return onClose
}

describe("ScheduleDialog frequency and failure rule", () => {
  it("offers hourly only when the organisation allows it, and states its real failure rule", () => {
    const { unmount } = render(<ScheduleDialog catalog={catalogFixture()} template={TEMPLATE} onClose={vi.fn()} />)
    expect(screen.queryByRole("radio", { name: "Hourly" })).toBeNull()
    expect(screen.getByText(/After several failures in a row/)).toBeInTheDocument()
    unmount()
    render(<ScheduleDialog catalog={catalogFixture({ settings: { minScheduleInterval: "hourly", pauseAfterConsecutiveFailures: 3 } })} template={TEMPLATE} onClose={vi.fn()} />)
    expect(screen.getByRole("radio", { name: "Hourly" })).toBeInTheDocument()
    expect(screen.getByText(/After 3 failures in a row/)).toBeInTheDocument()
  })
})

describe("ScheduleDialog", () => {
  beforeEach(() => {
    create.mockReset().mockResolvedValue({})
    update.mockReset().mockResolvedValue({})
  })

  it("saves a weekly schedule with the chosen days, time and format", async () => {
    const onClose = open()
    fireEvent.click(screen.getByLabelText("Wed"))
    fireEvent.change(screen.getByLabelText("Time"), { target: { value: "09:30" } })
    fireEvent.click(screen.getByLabelText("Excel"))
    fireEvent.click(screen.getByRole("button", { name: "Save schedule" }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toMatchObject({
      templateId: "t1",
      frequency: "weekly",
      daysOfWeek: [1, 3],
      timeOfDay: "09:30",
      format: "xlsx",
      deliveryChannels: ["in_app"],
      deliveryMode: "link",
      recipients: [],
    })
    expect(onClose).toHaveBeenCalled()
  })

  it("will not save a weekly schedule with no day, or an end before the start", async () => {
    open()
    fireEvent.click(screen.getByLabelText("Mon")) // untick the only day
    fireEvent.change(screen.getByLabelText(/Start date/), { target: { value: "2026-05-10" } })
    fireEvent.change(screen.getByLabelText(/End date/), { target: { value: "2026-05-01" } })
    fireEvent.click(screen.getByRole("button", { name: "Save schedule" }))
    expect(await screen.findByText("Choose at least one day.")).toBeInTheDocument()
    expect(screen.getByText(/end date must be on or after/)).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  it("offers the last day of the month for a monthly schedule", async () => {
    open()
    fireEvent.click(screen.getByRole("radio", { name: "Monthly" }))
    fireEvent.change(screen.getByLabelText("Day of the month"), { target: { value: "-1" } })
    fireEvent.click(screen.getByRole("button", { name: "Save schedule" }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toMatchObject({ frequency: "monthly", dayOfMonth: -1, daysOfWeek: undefined })
  })

  it("shows attachment options only when email is chosen and attachments are allowed", () => {
    open()
    expect(screen.queryByLabelText("Attach the file")).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText("Email"))
    expect(screen.getByLabelText("Attach the file")).toBeInTheDocument()
    expect(screen.getByText(/up to 10 MB/)).toBeInTheDocument()
  })

  it("never offers attachments or external addresses the organisation does not allow", () => {
    open(catalogFixture({ settings: { allowAttachments: false, allowExternalRecipients: false } }))
    fireEvent.click(screen.getByLabelText("Email"))
    expect(screen.queryByLabelText("Attach the file")).not.toBeInTheDocument()
    expect(screen.queryByRole("option", { name: "External address" })).not.toBeInTheDocument()
  })

  it("offers external addresses only to someone who may administer reports", () => {
    open(catalogFixture({ capabilities: { configure: false } }))
    expect(screen.queryByRole("option", { name: "External address" })).not.toBeInTheDocument()
  })

  it("lists only the channels the organisation allows", () => {
    open(catalogFixture({ settings: { allowedDeliveryChannels: ["in_app"] } }))
    expect(screen.queryByLabelText("Email")).not.toBeInTheDocument()
  })

  it("shows the server's problems next to their fields and stays open", async () => {
    create.mockRejectedValue(new ApiError(422, "bad", "SCHEDULE_INVALID", { name: "That name is taken.", recipients: "Too many recipients." }))
    const onClose = open()
    fireEvent.click(screen.getByRole("button", { name: "Save schedule" }))
    expect(await screen.findByText("That name is taken.")).toBeInTheDocument()
    expect(screen.getByText("Too many recipients.")).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it("edits an existing schedule through the update call", async () => {
    open(catalogFixture(), scheduleFixture({ name: "Weekly contracts" }))
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Renamed" } })
    fireEvent.click(screen.getByRole("button", { name: "Save schedule" }))
    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][0]).toMatchObject({ id: "s1", request: { name: "Renamed", templateId: "t1" } })
    expect(create).not.toHaveBeenCalled()
  })

  it("explains what happens when a run fails", () => {
    open()
    expect(screen.getByText("If a scheduled run fails")).toBeInTheDocument()
  })
})
