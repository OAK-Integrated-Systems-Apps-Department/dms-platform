import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import type { DocumentDto } from "@/lib/documents/api"
import { SignAndApproveDialog } from "./sign-and-approve-dialog"
import { SignDocumentDialog } from "./sign-document-dialog"

/**
 * Behaviour of the two signing dialogs, written BEFORE their `watch()` calls became `useWatch()` (FE-L3) and run unchanged
 * after: the form plumbing is the whole subject, so every test drives the real dialog - the real react-hook-form, schema
 * and step logic - and asserts what the mutation actually receives. Only the pieces that need a canvas, a file reader or
 * their own API calls are replaced (the visual-signature editor and the two placement steps), and the replacements echo
 * the props they are given so the tests can see what the dialog passes down.
 */

const mocks = vi.hoisted(() => ({
  signAndApprove: vi.fn(),
  signStandalone: vi.fn(),
  saved: { data: undefined as unknown, isFetched: true },
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("sonner", () => ({ toast: { success: mocks.toastSuccess, error: mocks.toastError } }))
vi.mock("@/lib/signatures/use-signatures", () => ({
  useSignatureActions: () => ({
    signAndApprove: { mutateAsync: mocks.signAndApprove },
    signStandalone: { mutateAsync: mocks.signStandalone },
  }),
  useMyVisualSignature: () => mocks.saved,
}))

type FieldProps = {
  kind: string
  text: string
  drawnImageBase64: string | null
  uploadedImageBase64: string | null
  onKindChange: (kind: "typed" | "drawn" | "uploaded") => void
  onTextChange: (text: string) => void
  onDrawnImageChange: (image: string | null) => void
  onUploadedImageChange: (image: string | null) => void
  textError?: string
  drawnImageError?: string
  uploadedImageError?: string
  disabled?: boolean
}

function FakeVisualFields(p: FieldProps) {
  return (
    <div data-testid="visual-fields" data-kind={p.kind} data-text={p.text} data-drawn={p.drawnImageBase64 ?? ""} data-uploaded={p.uploadedImageBase64 ?? ""} data-disabled={String(Boolean(p.disabled))}>
      <button type="button" onClick={() => p.onKindChange("typed")}>kind-typed</button>
      <button type="button" onClick={() => p.onKindChange("drawn")}>kind-drawn</button>
      <button type="button" onClick={() => p.onKindChange("uploaded")}>kind-uploaded</button>
      <input aria-label="typed signature" value={p.text} onChange={(e) => p.onTextChange(e.target.value)} />
      <button type="button" onClick={() => p.onDrawnImageChange("data:image/png;base64,DRAWN")}>draw</button>
      <button type="button" onClick={() => p.onUploadedImageChange("data:image/png;base64,UPLOADED")}>upload</button>
      <button type="button" onClick={() => p.onDrawnImageChange(null)}>clear-drawn</button>
      {p.textError && <p data-testid="text-error">{p.textError}</p>}
      {p.drawnImageError && <p data-testid="drawn-error">{p.drawnImageError}</p>}
      {p.uploadedImageError && <p data-testid="uploaded-error">{p.uploadedImageError}</p>}
    </div>
  )
}

type PlacementProps = {
  visualSignatureKind: string
  visualSignatureText: string
  visualSignatureImageBase64: string | null
  onBack: () => void
  onDone: () => void
}

function FakePlacement(p: PlacementProps) {
  return (
    <div data-testid="placement" data-kind={p.visualSignatureKind} data-text={p.visualSignatureText} data-image={p.visualSignatureImageBase64 ?? ""}>
      <button type="button" onClick={p.onBack}>placement-back</button>
      <button type="button" onClick={p.onDone}>placement-done</button>
    </div>
  )
}

vi.mock("./visual-signature-fields", () => ({ VisualSignatureFields: (p: FieldProps) => <FakeVisualFields {...p} /> }))
vi.mock("./signature-placement-step", () => ({ SignaturePlacementStep: (p: PlacementProps) => <FakePlacement {...p} /> }))
vi.mock("./document-signature-placement-step", () => ({ DocumentSignaturePlacementStep: (p: PlacementProps) => <FakePlacement {...p} /> }))

const DRAWN = "data:image/png;base64,DRAWN"
const UPLOADED = "data:image/png;base64,UPLOADED"

type Variant = {
  name: string
  mutation: typeof mocks.signAndApprove
  renderDialog: (onOpenChange: (open: boolean) => void) => ReturnType<typeof render>
  expectedRequest: (request: Record<string, unknown>) => unknown
  successMessage: string
}

const VARIANTS: Variant[] = [
  {
    name: "SignAndApproveDialog",
    mutation: mocks.signAndApprove,
    renderDialog: (onOpenChange) =>
      render(<SignAndApproveDialog taskId="task-1" documentTitle="Contract" stageLabel="Stage 1 of 2: Legal" onOpenChange={onOpenChange} />),
    expectedRequest: (request) => ({ taskId: "task-1", request: { meaning: "approved", ...request } }),
    successMessage: "Signed and approved.",
  },
  {
    name: "SignDocumentDialog",
    mutation: mocks.signStandalone,
    renderDialog: (onOpenChange) =>
      render(<SignDocumentDialog document={{ documentId: "doc-1", title: "Contract" } as DocumentDto} onOpenChange={onOpenChange} />),
    expectedRequest: (request) => ({ documentId: "doc-1", request }),
    successMessage: "Document signed.",
  },
]

const fields = () => screen.getByTestId("visual-fields")
const consent = () => screen.getByRole("checkbox", { name: /I intend to sign this document/ })
const next = () => fireEvent.click(screen.getByRole("button", { name: "Next" }))
const placement = () => screen.getByTestId("placement")
const stepLine = () => screen.getByText(/^Step \d of 3/).textContent
const password = () => screen.getByLabelText("Password") as HTMLInputElement
const submitButton = () => screen.getByRole("button", { name: /sign and approve|sign document|^sign/i }) as HTMLButtonElement

/** Fills step 1 with a typed name and consent, goes through the placement step, and lands on the password step. */
async function reachPasswordStep(typed = "Pat Example") {
  fireEvent.change(screen.getByLabelText("typed signature"), { target: { value: typed } })
  fireEvent.click(consent())
  next()
  fireEvent.click(await screen.findByRole("button", { name: "placement-done" }))
  await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3/))
}

const req = (over: Record<string, unknown> = {}) => ({
  comment: null,
  consent: true,
  password: "pw-123",
  totp: null,
  visualSignatureKind: "typed",
  visualSignatureText: "Pat Example",
  visualSignatureImageBase64: null,
  ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.saved = { data: undefined, isFetched: true }
  mocks.signAndApprove.mockResolvedValue({ instanceStatus: "in_progress" })
  mocks.signStandalone.mockResolvedValue({})
})

describe.each(VARIANTS)("$name - the typed signature, end to end", (v) => {
  it("walks the three steps and submits exactly the typed signature", async () => {
    const onOpenChange = vi.fn()
    v.renderDialog(onOpenChange)
    expect(stepLine()).toMatch(/Step 1 of 3 - Confirm your signature/)

    fireEvent.change(screen.getByLabelText("typed signature"), { target: { value: "  Pat Example  " } })
    fireEvent.click(consent())
    next()
    await screen.findByTestId("placement")
    expect(stepLine()).toMatch(/Step 2 of 3/)
    fireEvent.click(screen.getByRole("button", { name: "placement-done" }))
    await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3 - Confirm it's you/))
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(v.mutation).toHaveBeenCalledTimes(1))
    expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req()))
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(mocks.toastSuccess).toHaveBeenCalledWith(v.successMessage)
  })

  it("the comment is trimmed, and an empty one is sent as null", async () => {
    v.renderDialog(vi.fn())
    fireEvent.change(screen.getByLabelText(/Comment/), { target: { value: "  looks good  " } })
    await reachPasswordStep()
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ comment: "looks good" }))))
  })

  it("hands the placement step the typed mark, with no image", async () => {
    v.renderDialog(vi.fn())
    fireEvent.change(screen.getByLabelText("typed signature"), { target: { value: "Pat Example" } })
    fireEvent.click(consent())
    next()

    await screen.findByTestId("placement")
    expect(placement()).toHaveAttribute("data-kind", "typed")
    expect(placement()).toHaveAttribute("data-text", "Pat Example")
    expect(placement()).toHaveAttribute("data-image", "")
  })
})

describe.each(VARIANTS)("$name - consent and required signature", (v) => {
  it("will not leave step 1 without consent, and says why", async () => {
    v.renderDialog(vi.fn())
    fireEvent.change(screen.getByLabelText("typed signature"), { target: { value: "Pat Example" } })

    next()

    expect(await screen.findByText("Confirm you intend to sign this document")).toBeInTheDocument()
    expect(screen.queryByTestId("placement")).not.toBeInTheDocument()
    expect(stepLine()).toMatch(/Step 1 of 3/)
  })

  it("will not leave step 1 with a typed signature missing", async () => {
    v.renderDialog(vi.fn())
    fireEvent.click(consent())

    next()

    expect(await screen.findByTestId("text-error")).toHaveTextContent("Type your name for your signature")
    expect(screen.queryByTestId("placement")).not.toBeInTheDocument()
  })

  it("a typed signature over 150 characters is refused", async () => {
    v.renderDialog(vi.fn())
    fireEvent.change(screen.getByLabelText("typed signature"), { target: { value: "x".repeat(151) } })
    fireEvent.click(consent())

    next()

    expect(await screen.findByTestId("text-error")).toHaveTextContent("Keep it under 150 characters")
  })

  it("the consent box reflects the form value, and unticking it blocks again", async () => {
    v.renderDialog(vi.fn())
    expect(consent()).toHaveAttribute("aria-checked", "false")
    fireEvent.click(consent())
    expect(consent()).toHaveAttribute("aria-checked", "true")
    fireEvent.click(consent())
    expect(consent()).toHaveAttribute("aria-checked", "false")
  })
})

describe.each(VARIANTS)("$name - the drawn signature", (v) => {
  it("needs a drawing, then submits it with no text", async () => {
    v.renderDialog(vi.fn())
    fireEvent.click(screen.getByRole("button", { name: "kind-drawn" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "drawn"))
    fireEvent.click(consent())

    next()
    expect(await screen.findByTestId("drawn-error")).toHaveTextContent("Draw your signature")
    expect(screen.queryByTestId("placement")).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "draw" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-drawn", DRAWN))
    next()
    await screen.findByTestId("placement")
    expect(placement()).toHaveAttribute("data-kind", "drawn")
    expect(placement()).toHaveAttribute("data-image", DRAWN)
    fireEvent.click(screen.getByRole("button", { name: "placement-done" }))
    await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3/))
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() =>
      expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ visualSignatureKind: "drawn", visualSignatureText: null, visualSignatureImageBase64: DRAWN }))),
    )
  })

  it("clearing the drawing makes it required again", async () => {
    v.renderDialog(vi.fn())
    fireEvent.click(screen.getByRole("button", { name: "kind-drawn" }))
    fireEvent.click(screen.getByRole("button", { name: "draw" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-drawn", DRAWN))
    fireEvent.click(screen.getByRole("button", { name: "clear-drawn" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-drawn", ""))
    fireEvent.click(consent())

    next()

    expect(await screen.findByTestId("drawn-error")).toBeInTheDocument()
  })
})

describe.each(VARIANTS)("$name - the uploaded signature", (v) => {
  it("needs an image, then submits it with no text", async () => {
    v.renderDialog(vi.fn())
    fireEvent.click(screen.getByRole("button", { name: "kind-uploaded" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "uploaded"))
    fireEvent.click(consent())

    next()
    expect(await screen.findByTestId("uploaded-error")).toHaveTextContent("Upload an image of your signature")

    fireEvent.click(screen.getByRole("button", { name: "upload" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-uploaded", UPLOADED))
    next()
    await screen.findByTestId("placement")
    expect(placement()).toHaveAttribute("data-kind", "uploaded")
    expect(placement()).toHaveAttribute("data-image", UPLOADED)
    fireEvent.click(screen.getByRole("button", { name: "placement-done" }))
    await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3/))
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() =>
      expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ visualSignatureKind: "uploaded", visualSignatureText: null, visualSignatureImageBase64: UPLOADED }))),
    )
  })

  it("drawn and uploaded images never leak into each other: only the chosen kind's image is sent", async () => {
    v.renderDialog(vi.fn())
    fireEvent.click(screen.getByRole("button", { name: "kind-drawn" }))
    fireEvent.click(screen.getByRole("button", { name: "draw" }))
    fireEvent.click(screen.getByRole("button", { name: "kind-uploaded" }))
    fireEvent.click(screen.getByRole("button", { name: "upload" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-uploaded", UPLOADED))
    // Both slots are held, each separately.
    expect(fields()).toHaveAttribute("data-drawn", DRAWN)
    fireEvent.click(consent())
    next()
    await screen.findByTestId("placement")
    expect(placement()).toHaveAttribute("data-image", UPLOADED)
    fireEvent.click(screen.getByRole("button", { name: "placement-done" }))
    await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3/))
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() =>
      expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ visualSignatureKind: "uploaded", visualSignatureText: null, visualSignatureImageBase64: UPLOADED }))),
    )
  })

  it("switching back to typed sends the typed name and no image", async () => {
    v.renderDialog(vi.fn())
    fireEvent.click(screen.getByRole("button", { name: "kind-uploaded" }))
    fireEvent.click(screen.getByRole("button", { name: "upload" }))
    fireEvent.click(screen.getByRole("button", { name: "kind-typed" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "typed"))
    await reachPasswordStep("Back To Typed")
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ visualSignatureText: "Back To Typed", visualSignatureImageBase64: null }))))
  })
})

describe.each(VARIANTS)("$name - the saved signature", (v) => {
  it("pre-fills a saved typed signature, and it can be submitted as it is", async () => {
    mocks.saved = { data: { kind: "typed", typedText: "Saved Name", imageBase64: null }, isFetched: true }
    v.renderDialog(vi.fn())

    await waitFor(() => expect(fields()).toHaveAttribute("data-text", "Saved Name"))
    expect(fields()).toHaveAttribute("data-kind", "typed")
    fireEvent.click(consent())
    next()
    fireEvent.click(await screen.findByRole("button", { name: "placement-done" }))
    await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3/))
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ visualSignatureText: "Saved Name" }))))
  })

  it("pre-fills a saved drawn signature into the drawn slot only", async () => {
    mocks.saved = { data: { kind: "drawn", typedText: null, imageBase64: "data:image/png;base64,SAVEDDRAWN" }, isFetched: true }
    v.renderDialog(vi.fn())

    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "drawn"))
    expect(fields()).toHaveAttribute("data-drawn", "data:image/png;base64,SAVEDDRAWN")
    expect(fields()).toHaveAttribute("data-uploaded", "")
    expect(fields()).toHaveAttribute("data-text", "")
  })

  it("pre-fills a saved uploaded signature into the uploaded slot only", async () => {
    mocks.saved = { data: { kind: "uploaded", typedText: null, imageBase64: "data:image/png;base64,SAVEDUP" }, isFetched: true }
    v.renderDialog(vi.fn())

    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "uploaded"))
    expect(fields()).toHaveAttribute("data-uploaded", "data:image/png;base64,SAVEDUP")
    expect(fields()).toHaveAttribute("data-drawn", "")
  })

  it("a saved signature can be replaced: changing the type changes what is sent", async () => {
    mocks.saved = { data: { kind: "drawn", typedText: null, imageBase64: "data:image/png;base64,SAVEDDRAWN" }, isFetched: true }
    v.renderDialog(vi.fn())
    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "drawn"))

    fireEvent.click(screen.getByRole("button", { name: "kind-typed" }))
    await waitFor(() => expect(fields()).toHaveAttribute("data-kind", "typed"))
    await reachPasswordStep("New Typed")
    fireEvent.change(password(), { target: { value: "pw-123" } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(v.mutation).toHaveBeenCalledWith(v.expectedRequest(req({ visualSignatureKind: "typed", visualSignatureText: "New Typed", visualSignatureImageBase64: null }))))
  })

  it("shows a loading line until the saved signature has been looked up", () => {
    mocks.saved = { data: undefined, isFetched: false }
    v.renderDialog(vi.fn())

    expect(screen.getByText("Loading…")).toBeInTheDocument()
  })
})

describe.each(VARIANTS)("$name - the password step", (v) => {
  it("will not submit without a password", async () => {
    v.renderDialog(vi.fn())
    await reachPasswordStep()

    fireEvent.click(submitButton())

    expect(await screen.findByText("Enter your password")).toBeInTheDocument()
    expect(v.mutation).not.toHaveBeenCalled()
  })

  it("Back returns to the placement and clears the password", async () => {
    v.renderDialog(vi.fn())
    await reachPasswordStep()
    fireEvent.change(password(), { target: { value: "pw-123" } })

    fireEvent.click(screen.getByRole("button", { name: "Back" }))

    await screen.findByTestId("placement")
    expect(stepLine()).toMatch(/Step 2 of 3/)
    fireEvent.click(screen.getByRole("button", { name: "placement-done" }))
    await waitFor(() => expect(stepLine()).toMatch(/Step 3 of 3/))
    expect(password().value).toBe("")
  })

  it("the placement step's own Back returns to step 1 with everything kept", async () => {
    v.renderDialog(vi.fn())
    fireEvent.change(screen.getByLabelText("typed signature"), { target: { value: "Kept Name" } })
    fireEvent.click(consent())
    next()
    fireEvent.click(await screen.findByRole("button", { name: "placement-back" }))

    await waitFor(() => expect(stepLine()).toMatch(/Step 1 of 3/))
    expect(fields()).toHaveAttribute("data-text", "Kept Name")
    expect(consent()).toHaveAttribute("aria-checked", "true")
  })

  it("the password can be shown and hidden", async () => {
    v.renderDialog(vi.fn())

    expect(password().type).toBe("password")
    fireEvent.click(screen.getByRole("button", { name: "Show password" }))
    expect(password().type).toBe("text")
    fireEvent.click(screen.getByRole("button", { name: "Hide password" }))
    expect(password().type).toBe("password")
  })
})

describe.each(VARIANTS)("$name - a failed or slow signing", (v) => {
  it("shows the failure, clears only the password, stays on step 3 and keeps the dialog open", async () => {
    const onOpenChange = vi.fn()
    v.mutation.mockRejectedValueOnce(new ApiError(403, "no", "SIGNATURE_STEP_UP_FAILED"))
    v.renderDialog(onOpenChange)
    await reachPasswordStep("Kept Name")
    fireEvent.change(password(), { target: { value: "wrong-pw" } })

    fireEvent.click(submitButton())

    expect(await screen.findByText("Your password or authenticator app code is incorrect.")).toBeInTheDocument()
    expect(password().value).toBe("")
    expect(stepLine()).toMatch(/Step 3 of 3/)
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(mocks.toastSuccess).not.toHaveBeenCalled()
  })

  it("a retry after a failure sends the same signature with the new password, and clears the message", async () => {
    v.mutation.mockRejectedValueOnce(new ApiError(403, "no", "SIGNATURE_STEP_UP_FAILED"))
    const onOpenChange = vi.fn()
    v.renderDialog(onOpenChange)
    await reachPasswordStep("Kept Name")
    fireEvent.change(password(), { target: { value: "wrong-pw" } })
    fireEvent.click(submitButton())
    await screen.findByText("Your password or authenticator app code is incorrect.")

    fireEvent.change(password(), { target: { value: "right-pw" } })
    fireEvent.click(submitButton())

    await waitFor(() => expect(v.mutation).toHaveBeenCalledTimes(2))
    expect(v.mutation).toHaveBeenLastCalledWith(v.expectedRequest(req({ password: "right-pw", visualSignatureText: "Kept Name" })))
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(screen.queryByText("Your password or authenticator app code is incorrect.")).not.toBeInTheDocument()
  })

  it("an unrecognised failure gets a generic message", async () => {
    v.mutation.mockRejectedValueOnce(new Error("network down"))
    v.renderDialog(vi.fn())
    await reachPasswordStep()
    fireEvent.change(password(), { target: { value: "pw-123" } })

    fireEvent.click(submitButton())

    expect(await screen.findByText("Something went wrong. Please try again.")).toBeInTheDocument()
  })

  it("while signing is in progress the buttons are disabled, and the editor is told it is disabled", async () => {
    let finish!: (value: unknown) => void
    v.mutation.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    v.renderDialog(vi.fn())
    await reachPasswordStep()
    fireEvent.change(password(), { target: { value: "pw-123" } })

    fireEvent.click(submitButton())

    await waitFor(() => expect(submitButton()).toBeDisabled())
    expect(screen.getByRole("button", { name: "Back" })).toBeDisabled()
    expect(fields()).toHaveAttribute("data-disabled", "true")
    finish({ instanceStatus: "in_progress" })
    await waitFor(() => expect(mocks.toastSuccess).toHaveBeenCalled())
  })

  it("Cancel closes the dialog without signing", async () => {
    const onOpenChange = vi.fn()
    v.renderDialog(onOpenChange)

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))

    // The dialog library calls onOpenChange(false, details): only the first argument matters here.
    await waitFor(() => expect(onOpenChange).toHaveBeenCalled())
    expect(onOpenChange.mock.calls[0][0]).toBe(false)
    expect(v.mutation).not.toHaveBeenCalled()
  })
})

describe("SignAndApproveDialog - what only it does", () => {
  it("always approves, and says what became of the workflow", async () => {
    mocks.signAndApprove.mockResolvedValue({ instanceStatus: "approved" })
    const onOpenChange = vi.fn()
    VARIANTS[0].renderDialog(onOpenChange)
    await reachPasswordStep()
    fireEvent.change(password(), { target: { value: "pw-123" } })

    fireEvent.click(submitButton())

    await waitFor(() => expect(mocks.toastSuccess).toHaveBeenCalledWith("Signed and approved. The workflow is now approved."))
    expect(mocks.signAndApprove.mock.calls[0][0].request.meaning).toBe("approved")
  })

  it("shows the stage, the document and the newer-version notice", () => {
    render(<SignAndApproveDialog taskId="t" documentTitle="Contract" stageLabel="Stage 2 of 3: Legal review" versionChanged onOpenChange={vi.fn()} />)

    expect(screen.getByText("Contract")).toBeInTheDocument()
    expect(screen.getByText("Stage 2 of 3: Legal review")).toBeInTheDocument()
    expect(screen.getByText(/A newer version of this document was uploaded/)).toBeInTheDocument()
  })
})

describe("SignDocumentDialog - what only it does", () => {
  it("never sends a meaning (nothing is approved here)", async () => {
    VARIANTS[1].renderDialog(vi.fn())
    await reachPasswordStep()
    fireEvent.change(password(), { target: { value: "pw-123" } })

    fireEvent.click(submitButton())

    await waitFor(() => expect(mocks.signStandalone).toHaveBeenCalledTimes(1))
    expect(mocks.signStandalone.mock.calls[0][0].request).not.toHaveProperty("meaning")
    expect(mocks.signStandalone.mock.calls[0][0].documentId).toBe("doc-1")
  })
})