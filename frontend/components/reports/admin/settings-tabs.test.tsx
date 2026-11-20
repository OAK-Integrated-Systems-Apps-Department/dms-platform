import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"

import { GeneralTab } from "./general-tab"
import { LimitsTab } from "./limits-tab"
import { OutputTab } from "./output-tab"

const save = vi.fn()
const generateSample = vi.fn()
let stored: Record<string, unknown>
let ceilings: Record<string, number | boolean>

vi.mock("@/lib/reports/use-reports", () => ({
  useAdminSettings: () => ({ data: { settings: stored, ceilings }, isPending: false, isError: false }),
  useSaveAdminSettings: () => ({ mutateAsync: save, isPending: false }),
}))
vi.mock("@/lib/reports/api", () => ({ generateSample: (...args: unknown[]) => generateSample(...args) }))
vi.mock("@/lib/download-file", () => ({ saveDownloadedFile: vi.fn() }))
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const STORED = {
  enabled: true, schedulingEnabled: true, allowedFormats: ["pdf", "xlsx", "csv"], defaultFormat: "pdf", csvDelimiter: ",",
  csvIncludeBom: true, dateFormat: "yyyy-MM-dd", numberFormat: "#,##0.00", defaultTimeZone: "Asia/Colombo", defaultLocale: "en-GB",
  headerText: "Oak", footerText: "", confidentialityNotice: "", showLogo: true, showFilterSummary: true, showGeneratedBy: true,
  pdfPageSize: "A4", pdfOrientation: "landscape", maxRows: 50000, maxPdfRows: 5000, overLimitBehaviour: "fail", maxConcurrentRuns: 3,
  maxQueuedPerUser: 5, // in the GET but not accepted by the PUT
  fileRetentionDays: 14, maxTemplatesPerUser: 50, maxSchedulesPerUser: 10, maxSchedulesPerTenant: 200, minScheduleInterval: "daily",
  maxRecipientsPerSchedule: 25, allowedDeliveryChannels: ["in_app", "email"], deliveryModeDefault: "link", allowAttachments: true,
  maxAttachmentMb: 10, allowExternalRecipients: false, externalDomainAllowlist: [], failureNotifyAdmins: true, failureAdminUserIds: [],
  pauseAfterConsecutiveFailures: 3, catchUpWindowHours: 6, skipEmptyScheduledReports: false, includeInactiveByDefault: false,
  defaultColumns: ["title"], defaultSort: [],
}

describe("report settings tabs", () => {
  beforeEach(() => {
    save.mockReset()
    save.mockResolvedValue({})
    generateSample.mockReset()
    stored = { ...STORED }
    ceilings = { maxRows: 100000, maxPdfRows: 10000, maxConcurrentRuns: 10 }
  })

  it("offers no save until something changes", () => {
    render(<GeneralTab />)
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled()
  })

  it("saves the WHOLE settings object - this tab's edit merged over everything else - so no other tab's value is wiped", async () => {
    render(<GeneralTab />)
    fireEvent.change(screen.getByLabelText("Default time zone"), { target: { value: "Europe/London" } })
    expect(screen.getByText("Not saved yet")).toBeInTheDocument()
    expect(screen.getByText("Asia/Colombo")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    const sent = save.mock.calls[0][0] as Record<string, unknown>
    expect(sent.defaultTimeZone).toBe("Europe/London")
    expect(sent.maxRows).toBe(50000) // another tab's value is sent back unchanged
    expect(sent.headerText).toBe("Oak")
    expect(sent.allowedDeliveryChannels).toEqual(["in_app", "email"])
    expect(sent).not.toHaveProperty("maxQueuedPerUser") // not something the server accepts
  })

  it("shows the server's per-field problems next to the field and keeps the edit", async () => {
    save.mockRejectedValue(new ApiError(422, "Some report settings are not valid.", "REPORT_SETTINGS_INVALID", {
      defaultTimeZone: "Use an IANA time zone such as Europe/London or Asia/Colombo.",
    }))
    render(<GeneralTab />)
    fireEvent.change(screen.getByLabelText("Default time zone"), { target: { value: "Mars/Base" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    expect(await screen.findByText("Use an IANA time zone such as Europe/London or Asia/Colombo.")).toBeInTheDocument()
    expect(screen.getByLabelText("Default time zone")).toHaveValue("Mars/Base")
    // typing again clears that field's message
    fireEvent.change(screen.getByLabelText("Default time zone"), { target: { value: "Europe/Paris" } })
    expect(screen.queryByText(/Use an IANA time zone/)).not.toBeInTheDocument()
  })

  it("discards unsaved edits", () => {
    render(<GeneralTab />)
    fireEvent.change(screen.getByLabelText("Locale"), { target: { value: "si-LK" } })
    fireEvent.click(screen.getByRole("button", { name: "Discard" }))
    expect(screen.getByLabelText("Locale")).toHaveValue("en-GB")
  })

  it("shows the platform ceiling beside each limit", () => {
    render(<LimitsTab />)
    expect(screen.getByLabelText("Most rows in a report")).toHaveValue(50000)
    expect(screen.getByText("Platform ceiling: 100,000")).toBeInTheDocument()
    expect(screen.getByText("Platform ceiling: 10,000")).toBeInTheDocument()
  })

  it("cannot be edited without the configure permission", () => {
    render(<LimitsTab canEdit={false} />)
    expect(screen.getByLabelText("Most rows in a report")).toBeDisabled()
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument()
  })

  it("makes the live sample from the settings on screen, not the saved ones", async () => {
    generateSample.mockResolvedValue({ blob: new Blob(["x"]), filename: "sample.pdf" })
    render(<OutputTab />)
    fireEvent.change(screen.getByLabelText("Header text"), { target: { value: "New header" } })
    fireEvent.click(screen.getByRole("button", { name: "Download sample PDF" }))
    await waitFor(() => expect(generateSample).toHaveBeenCalledTimes(1))
    expect(generateSample.mock.calls[0][0]).toBe("pdf")
    expect((generateSample.mock.calls[0][1] as Record<string, unknown>).headerText).toBe("New header")
    expect(save).not.toHaveBeenCalled()
  })
})
