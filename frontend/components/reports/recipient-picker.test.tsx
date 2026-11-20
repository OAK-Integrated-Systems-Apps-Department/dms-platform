import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import type { Recipient } from "@/lib/reports/api"

import { RecipientPicker } from "./recipient-picker"

vi.mock("@/lib/reports/use-reports", () => ({
  useRecipientDirectory: () => ({
    users: [{ value: "u2", label: "Ben Silva" }],
    roles: [{ value: "r1", label: "Auditors" }],
    departments: [{ value: "d1", label: "Finance" }],
    loading: false,
  }),
}))

function setup(over: { allowExternal?: boolean; value?: Recipient[]; max?: number } = {}) {
  const onChange = vi.fn()
  render(<RecipientPicker value={over.value ?? []} onChange={onChange} allowExternal={over.allowExternal ?? false} max={over.max} />)
  return onChange
}

describe("RecipientPicker", () => {
  it("always explains whose access applies", () => {
    setup()
    expect(screen.getByText(/their own/)).toBeInTheDocument()
  })

  it("offers external addresses only when allowed, with the extra explanation", () => {
    setup({ allowExternal: false })
    expect(screen.queryByRole("option", { name: "External address" })).not.toBeInTheDocument()
    expect(screen.queryByText(/your/)).not.toBeInTheDocument()
  })

  it("adds an external address after checking it, and says what access it gets", () => {
    const onChange = setup({ allowExternal: true })
    fireEvent.change(screen.getByLabelText("Send to"), { target: { value: "email" } })
    expect(screen.getByText(/External addresses get a file/)).toBeInTheDocument()
    const input = screen.getByLabelText("External email address")
    fireEvent.change(input, { target: { value: "not-an-address" } })
    fireEvent.click(screen.getByRole("button", { name: "Add" }))
    expect(screen.getByRole("alert")).toHaveTextContent("valid email")
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: "partner@example.com" } })
    fireEvent.click(screen.getByRole("button", { name: "Add" }))
    expect(onChange).toHaveBeenCalledWith([{ type: "email", ref: "partner@example.com" }])
  })

  it("shows chosen recipients by name and removes one", () => {
    const onChange = setup({ value: [{ type: "user", ref: "u2" }, { type: "role", ref: "r1" }] })
    expect(screen.getByText("Ben Silva")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Remove Auditors" }))
    expect(onChange).toHaveBeenCalledWith([{ type: "user", ref: "u2" }])
  })

  it("stops adding at the limit", () => {
    setup({ value: [{ type: "user", ref: "u2" }], max: 1 })
    expect(screen.getByText(/at most 1 recipients/)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled()
  })
})
