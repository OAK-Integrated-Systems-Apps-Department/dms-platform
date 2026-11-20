import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import type { AdminField } from "@/lib/reports/api"

import { FieldsTab } from "./fields-tab"

const save = vi.fn()
let fields: AdminField[]

vi.mock("@/lib/reports/use-reports", () => ({
  useAdminFields: () => ({ data: fields, isPending: false, isError: false }),
  useSaveAdminFields: () => ({ mutateAsync: save, isPending: false }),
}))
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const field = (key: string, label: string, over: Partial<AdminField> = {}): AdminField => ({
  key, label, type: "text", custom: false, canSelect: true, canFilter: true, canSort: true, canGroup: false, defaultSelected: false,
  override: null, ...over,
})

describe("FieldsTab", () => {
  beforeEach(() => {
    save.mockReset()
    save.mockResolvedValue([])
    fields = [
      field("title", "Title", { defaultSelected: true }),
      field("owner", "Owner", { override: { fieldKey: "owner", labelOverride: "Author" } }),
      field("status", "Status", { canGroup: true }),
    ]
  })

  it("disables a switch for something the field cannot do", () => {
    render(<FieldsTab />)
    expect(screen.getByRole("switch", { name: "Group: Title" })).toHaveAttribute("aria-disabled", "true")
    expect(screen.getByRole("switch", { name: "Group: Status" })).not.toHaveAttribute("aria-disabled", "true")
  })

  it("sends every existing override plus the new one, and leaves untouched fields out", async () => {
    render(<FieldsTab />)
    fireEvent.click(screen.getByRole("switch", { name: "Filter: Status" }))
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    const sent = save.mock.calls[0][0] as { fieldKey: string; filterable?: boolean; labelOverride?: string | null }[]
    expect(sent.map((o) => o.fieldKey).sort()).toEqual(["owner", "status"]) // title untouched
    expect(sent.find((o) => o.fieldKey === "status")?.filterable).toBe(false)
    expect(sent.find((o) => o.fieldKey === "owner")?.labelOverride).toBe("Author")
  })

  it("asks before turning a field off, and says what it affects", async () => {
    render(<FieldsTab />)
    fireEvent.click(screen.getByRole("switch", { name: "Reportable: Status" }))
    expect(await screen.findByText(/No official report or active schedule uses it/)).toBeInTheDocument()
    expect(screen.getByRole("switch", { name: "Reportable: Status", hidden: true })).toBeChecked() // not yet
    fireEvent.click(screen.getByRole("button", { name: "Turn off" }))
    await waitFor(() => expect(screen.getByRole("switch", { name: "Reportable: Status" })).not.toBeChecked())
  })

  it("says how many official reports and schedules use a field before it is turned off", async () => {
    fields = [field("status", "Status", { officialTemplatesUsing: 2, schedulesUsing: 1 })]
    render(<FieldsTab />)
    fireEvent.click(screen.getByRole("switch", { name: "Reportable: Status" }))
    expect(await screen.findByText(/2 official reports and 1 schedule use it/)).toBeInTheDocument()
  })

  it("puts one field back to its default, and all of them", async () => {
    render(<FieldsTab />)
    const row = document.querySelector('[data-field="owner"]') as HTMLElement
    expect(within(row).getByLabelText("Label for Owner")).toHaveValue("Author")
    fireEvent.click(screen.getByRole("button", { name: "Reset Owner to default" }))
    expect(within(row).getByLabelText("Label for Owner")).toHaveValue("")
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    await waitFor(() => expect(save).toHaveBeenCalledWith([]))
  })

  it("moves a field up and records the order", async () => {
    render(<FieldsTab />)
    fireEvent.click(screen.getByRole("button", { name: "Move Status up" }))
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    const sent = save.mock.calls[0][0] as { fieldKey: string; displayOrder: number }[]
    const order = Object.fromEntries(sent.map((o) => [o.fieldKey, o.displayOrder]))
    expect(order.status).toBeLessThan(order.owner)
    expect(order.title).toBe(0)
  })

  it("shows the server's problems under the field they belong to", async () => {
    save.mockRejectedValue(new ApiError(422, "bad", "REPORT_FIELD_SETTINGS_INVALID", { owner: "The label can be at most 120 characters." }))
    render(<FieldsTab />)
    fireEvent.change(screen.getByLabelText("Label for Owner"), { target: { value: "x" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))
    const row = document.querySelector('[data-field="owner"]') as HTMLElement
    expect(await within(row).findByText("The label can be at most 120 characters.")).toBeInTheDocument()
  })

  it("is read-only without the configure permission", () => {
    render(<FieldsTab canEdit={false} />)
    expect(screen.getByRole("switch", { name: "Filter: Status" })).toHaveAttribute("aria-disabled", "true")
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Reset all" })).not.toBeInTheDocument()
  })
})
