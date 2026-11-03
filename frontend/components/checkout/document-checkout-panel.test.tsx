import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { CheckoutStatusDto } from "@/lib/checkout/api"

import { DocumentCheckoutPanel } from "./document-checkout-panel"

// The panel's job is to show the right state and offer only what the caller may do - so the data
// hooks are replaced and each test sets the lock state and the caller's permissions directly.
let status: CheckoutStatusDto | undefined
let granted: string[] = []
let editing: { enabled: boolean; mode: "exclusive" | "simultaneous"; extensions: string[] } | undefined
const checkOut = vi.fn()
const discard = vi.fn()

vi.mock("@/lib/checkout/use-checkout", () => ({
  useCheckoutStatus: () => ({ data: status, isPending: false, isError: false }),
  useEditingConfig: () => ({ data: editing }),
  useCheckoutActions: () => ({
    checkOut: { mutate: checkOut, isPending: false },
    checkIn: { mutate: vi.fn(), isPending: false },
    discard: { mutate: discard, isPending: false },
    forceRelease: { mutateAsync: vi.fn(), isPending: false },
    hold: { mutateAsync: vi.fn(), isPending: false },
  }),
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

const CHECK_OUT = "cico.check-out-check-in-documents.check-out"
const CHECK_IN = "cico.check-out-check-in-documents.check-in"
const DISCARD = "cico.check-out-check-in-documents.discard-checkout"
const FORCE = "cico.force-release-lock"
const PLACE_HOLD = "crm.place-legal-hold"
const LIFT_HOLD = "crm.lift-legal-hold"

function state(overrides: Partial<CheckoutStatusDto> = {}): CheckoutStatusDto {
  return {
    state: "available",
    checkedOutById: null,
    checkedOutByName: null,
    checkedOutAt: null,
    checkoutExpiresAt: null,
    checkedOutByMe: false,
    legalHold: false,
    legalHoldReason: null,
    legalHoldAt: null,
    signatureLocked: false,
    lastEditedById: null,
    lastEditedByName: null,
    lastEditedAt: null,
    lastEditedVersion: 0,
    ...overrides,
  }
}

describe("DocumentCheckoutPanel", () => {
  beforeEach(() => {
    checkOut.mockClear()
    discard.mockClear()
    granted = []
    editing = undefined
  })

  it("offers Edit online only when the engine is on, edits this file type, and the caller may start a session", () => {
    editing = { enabled: true, mode: "exclusive", extensions: ["docx", "xlsx"] }
    status = state()
    granted = [CHECK_OUT]

    const word = render(<DocumentCheckoutPanel documentId="doc-1" fileName="Contract.DOCX" />)
    expect(screen.getByRole("link", { name: /edit online/i })).toHaveAttribute("href", "/documents/doc-1/edit")
    word.unmount()

    // A type the engine cannot edit: check-out / download is the way, not a button that would only fail.
    const pdf = render(<DocumentCheckoutPanel documentId="doc-1" fileName="scan.pdf" />)
    expect(screen.queryByRole("link", { name: /edit online/i })).not.toBeInTheDocument()
    pdf.unmount()

    // Engine off or down.
    editing = { enabled: false, mode: "exclusive", extensions: [] }
    const off = render(<DocumentCheckoutPanel documentId="doc-1" fileName="Contract.docx" />)
    expect(screen.queryByRole("link", { name: /edit online/i })).not.toBeInTheDocument()
    off.unmount()

    // No permission to check out.
    editing = { enabled: true, mode: "exclusive", extensions: ["docx"] }
    granted = []
    render(<DocumentCheckoutPanel documentId="doc-1" fileName="Contract.docx" />)
    expect(screen.queryByRole("link", { name: /edit online/i })).not.toBeInTheDocument()
  })

  it("says a PDF cannot be edited in the browser and what to do instead - but only for types the engine cannot edit", () => {
    editing = { enabled: true, mode: "exclusive", extensions: ["docx", "pptx", "txt"] }
    status = state()
    granted = [CHECK_OUT]

    const pdf = render(<DocumentCheckoutPanel documentId="doc-1" fileName="scan.pdf" />)
    expect(screen.getByText(/can.t be edited in the browser/i)).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /edit online/i })).not.toBeInTheDocument()
    pdf.unmount()

    // PowerPoint and text are editable: the button, no apology.
    for (const name of ["deck.pptx", "notes.txt"]) {
      const view = render(<DocumentCheckoutPanel documentId="doc-1" fileName={name} />)
      expect(screen.getByRole("link", { name: /edit online/i })).toBeInTheDocument()
      expect(screen.queryByText(/can.t be edited in the browser/i)).not.toBeInTheDocument()
      view.unmount()
    }

    // Editing off for the organisation: nothing about the browser at all.
    editing = { enabled: false, mode: "exclusive", extensions: [] }
    render(<DocumentCheckoutPanel documentId="doc-1" fileName="scan.pdf" />)
    expect(screen.queryByText(/can.t be edited in the browser/i)).not.toBeInTheDocument()
  })

  it("does not offer Edit online while somebody else has the document checked out", () => {
    editing = { enabled: true, mode: "exclusive", extensions: ["docx"] }
    status = state({
      state: "checked_out",
      checkedOutById: "u-2",
      checkedOutByName: "Bob Perera",
      checkedOutAt: "2026-11-02T10:00:00Z",
      checkoutExpiresAt: "2026-11-05T10:00:00Z",
    })
    granted = [CHECK_OUT]
    render(<DocumentCheckoutPanel documentId="doc-1" fileName="Contract.docx" />)
    expect(screen.queryByRole("link", { name: /edit online/i })).not.toBeInTheDocument()
  })

  it("offers Check out on an available document - and only to someone allowed to", () => {
    status = state()
    granted = [CHECK_OUT]
    const { unmount } = render(<DocumentCheckoutPanel documentId="doc-1" />)
    fireEvent.click(screen.getByRole("button", { name: /check out/i }))
    expect(checkOut).toHaveBeenCalledTimes(1)
    unmount()

    granted = []
    render(<DocumentCheckoutPanel documentId="doc-1" />)
    expect(screen.getByText("Available")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /check out/i })).not.toBeInTheDocument()
  })

  it("tells everyone who holds it, and gives only the holder check-in and discard", () => {
    status = state({
      state: "checked_out",
      checkedOutById: "u-2",
      checkedOutByName: "Bob Perera",
      checkedOutAt: "2026-11-02T10:00:00Z",
      checkoutExpiresAt: "2026-11-05T10:00:00Z",
    })
    granted = [CHECK_OUT, CHECK_IN, DISCARD]
    render(<DocumentCheckoutPanel documentId="doc-1" />)

    expect(screen.getByText("Checked out")).toBeInTheDocument()
    expect(screen.getByText(/by Bob Perera since/)).toBeInTheDocument()
    // Not the holder: nothing to check in or discard, and no second check-out.
    expect(screen.queryByRole("button", { name: /check in/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /discard/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^check out$/i })).not.toBeInTheDocument()
  })

  it("lets the holder check in or discard", () => {
    status = state({
      state: "checked_out",
      checkedOutById: "u-1",
      checkedOutByName: "Alice",
      checkedOutAt: "2026-11-02T10:00:00Z",
      checkoutExpiresAt: "2026-11-05T10:00:00Z",
      checkedOutByMe: true,
    })
    granted = [CHECK_IN, DISCARD]
    render(<DocumentCheckoutPanel documentId="doc-1" />)

    expect(screen.getByText(/by you since/)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /check in edited file/i })).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: /discard check-out/i }))
    expect(discard).toHaveBeenCalledTimes(1)
  })

  it("offers force release only to someone else with that permission", () => {
    status = state({
      state: "checked_out",
      checkedOutById: "u-2",
      checkedOutByName: "Bob Perera",
      checkedOutAt: "2026-11-02T10:00:00Z",
      checkoutExpiresAt: "2026-11-05T10:00:00Z",
    })
    granted = [FORCE]
    render(<DocumentCheckoutPanel documentId="doc-1" />)
    expect(screen.getByRole("button", { name: /force release/i })).toBeInTheDocument()
  })

  it("on legal hold, shows why and offers no check-out - only lifting the hold", () => {
    status = state({
      state: "legal_hold",
      legalHold: true,
      legalHoldReason: "Litigation 2026-114",
      legalHoldAt: "2026-11-02T10:00:00Z",
    })
    granted = [CHECK_OUT, PLACE_HOLD, LIFT_HOLD]
    render(<DocumentCheckoutPanel documentId="doc-1" />)

    expect(screen.getAllByText(/legal hold/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/Litigation 2026-114/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^check out$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /place legal hold/i })).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: /lift legal hold/i })).toBeInTheDocument()
  })

  it("a signed document cannot be checked out, and says how to unlock it", () => {
    status = state({ state: "signature_locked", signatureLocked: true })
    granted = [CHECK_OUT]
    render(<DocumentCheckoutPanel documentId="doc-1" />)

    expect(screen.getByText("Signed and locked")).toBeInTheDocument()
    expect(screen.getByText(/carries a digital signature/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^check out$/i })).not.toBeInTheDocument()
  })
})
