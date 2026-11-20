import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import { SCAN_FILE_ACCEPT } from "@/lib/documents/scan-upload-files"
import { DocumentUploadForm } from "./document-upload-form"

const mocks = vi.hoisted(() => ({
  uploadDocument: vi.fn(),
  fetchOcrStatus: vi.fn(),
  fetchDocumentMetadata: vi.fn(),
  rerunOcr: vi.fn(),
  saveDocumentMetadata: vi.fn(),
  schemaFields: [] as unknown[],
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock("sonner", () => ({ toast: { error: mocks.toastError, success: mocks.toastSuccess } }))
vi.mock("@/components/folders/folder-select", () => ({ FolderSelect: () => <div data-testid="folder-select" /> }))
vi.mock("@/lib/document-types/api", () => ({
  fetchAllDocumentTypes: async () => [
    { documentTypeId: "t1", typeCode: "GENERAL", typeName: "General", status: "active" },
    { documentTypeId: "t2", typeCode: "INVOICE", typeName: "Invoice", status: "active" },
  ],
}))
vi.mock("@/lib/document-type-fields/api", () => ({
  fetchDocumentTypeSchema: async () => ({ documentTypeId: "t2", typeCode: "INVOICE", typeName: "Invoice", schemaVersion: 1, fields: mocks.schemaFields }),
}))
vi.mock("@/lib/documents/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/documents/api")>()),
  uploadDocument: mocks.uploadDocument,
  fetchOcrStatus: mocks.fetchOcrStatus,
  fetchDocumentMetadata: mocks.fetchDocumentMetadata,
  rerunOcr: mocks.rerunOcr,
  saveDocumentMetadata: mocks.saveDocumentMetadata,
}))

const CLEAN_TICKET = {
  scanResultId: "s1",
  status: "clean",
  documentId: "d1",
  versionId: "v1",
  originalFilename: "scan.pdf",
  sizeBytes: null,
  threatName: null,
  message: null,
  retrying: false,
  submittedAt: "2026-01-01T00:00:00Z",
  completedAt: null,
}

function renderForm() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <DocumentUploadForm />
    </QueryClientProvider>,
  )
}

const fileInput = () => document.getElementById("file") as HTMLInputElement
const uploadButton = () => screen.getByRole("button", { name: /^upload$/i }) as HTMLButtonElement

function choose(file: File | { name: string; size: number }) {
  fireEvent.change(fileInput(), { target: { files: [file] } })
}

function fileOf(name: string, size = 1500): File {
  const f = new File(["x"], name, { type: "application/octet-stream" })
  Object.defineProperty(f, "size", { value: size })
  return f
}

/**
 * Renders the form and picks a document type. The type has to be picked: the Upload button and the upload itself read the
 * chosen type, not the default the dropdown displays (see the report on this change - a separate, existing behaviour).
 */
async function ready() {
  renderForm()
  await waitFor(() => expect(screen.getByLabelText("Document type")).toHaveValue("GENERAL"))
  fireEvent.change(screen.getByLabelText("Document type"), { target: { value: "INVOICE" } })
  await waitFor(() => expect(screen.getByLabelText("Document type")).toHaveValue("INVOICE"))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.schemaFields = []
  mocks.uploadDocument.mockResolvedValue(CLEAN_TICKET)
  mocks.fetchOcrStatus.mockResolvedValue({ id: "o1", status: "processing", fields: {}, errorCode: null, friendlyError: null })
  mocks.fetchDocumentMetadata.mockResolvedValue({})
  mocks.rerunOcr.mockResolvedValue(undefined)
})

describe("scan upload form - the file picker", () => {
  it("offers exactly the six approved extensions, .tif included", async () => {
    await ready()

    expect(fileInput().getAttribute("accept")).toBe(SCAN_FILE_ACCEPT)
    expect(fileInput().getAttribute("accept")).toBe(".pdf,.png,.jpg,.jpeg,.tif,.tiff")
  })

  it("says which types it takes", async () => {
    await ready()

    expect(screen.getByText("PDF, PNG, JPEG, or TIFF.")).toBeInTheDocument()
  })
})

describe("scan upload form - an unsupported file", () => {
  it("shows a validation message at once and does not hold the file", async () => {
    await ready()

    choose(fileOf("setup.exe"))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/setup\.exe/)
    expect(alert).toHaveTextContent(/not a supported file type/i)
    expect(uploadButton()).toBeDisabled()
    expect(fileInput().value).toBe("")
  })

  it("never calls uploadDocument", async () => {
    await ready()

    choose(fileOf("setup.exe"))
    fireEvent.click(uploadButton())

    expect(mocks.uploadDocument).not.toHaveBeenCalled()
  })

  it.each(["archive.zip", "notes.txt", "photo.gif", "scan", "scan.", ".pdf"])("rejects %s", async (name) => {
    await ready()

    choose(fileOf(name))

    expect(await screen.findByRole("alert")).toHaveTextContent(/not a supported file type/i)
    expect(uploadButton()).toBeDisabled()
    expect(mocks.uploadDocument).not.toHaveBeenCalled()
  })

  it("rejects a zero-byte file with its own message", async () => {
    await ready()

    choose(fileOf("scan.pdf", 0))

    expect(await screen.findByRole("alert")).toHaveTextContent(/empty/i)
    expect(uploadButton()).toBeDisabled()
    expect(mocks.uploadDocument).not.toHaveBeenCalled()
  })

  it("marks the field invalid for assistive technology", async () => {
    await ready()

    choose(fileOf("setup.exe"))

    await screen.findByRole("alert")
    expect(fileInput()).toHaveAttribute("aria-invalid", "true")
    expect(fileInput().getAttribute("aria-describedby")).toBe("file-error")
  })
})

describe("scan upload form - recovering", () => {
  it("a valid file after an invalid one clears the error and enables Upload", async () => {
    await ready()
    choose(fileOf("setup.exe"))
    await screen.findByRole("alert")

    choose(fileOf("scan.pdf"))

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument())
    expect(uploadButton()).toBeEnabled()
    expect(fileInput()).not.toHaveAttribute("aria-invalid")
    expect(screen.getByText("PDF, PNG, JPEG, or TIFF.")).toBeInTheDocument()
  })

  it("an invalid file after a valid one leaves nothing selected - the earlier file cannot be uploaded by mistake", async () => {
    await ready()
    choose(fileOf("scan.pdf"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    choose(fileOf("setup.exe"))

    await screen.findByRole("alert")
    expect(uploadButton()).toBeDisabled()
    fireEvent.click(uploadButton())
    expect(mocks.uploadDocument).not.toHaveBeenCalled()
  })

  it("clearing the selection removes the error", async () => {
    await ready()
    choose(fileOf("setup.exe"))
    await screen.findByRole("alert")

    fireEvent.change(fileInput(), { target: { files: [] } })

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument())
  })
})

describe("scan upload form - submission revalidates", () => {
  it("does not upload a file that no longer passes when Upload is pressed", async () => {
    await ready()
    // Passes at selection (the first read of its name), fails at submission (the second).
    let reads = 0
    const shifty = {
      get name() {
        reads += 1
        return reads === 1 ? "scan.pdf" : "scan.exe"
      },
      size: 1500,
      type: "application/pdf",
    }
    choose(shifty as unknown as File)
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    expect(mocks.uploadDocument).not.toHaveBeenCalled()
    expect(await screen.findByRole("alert")).toHaveTextContent(/not a supported file type/i)
    expect(uploadButton()).toBeDisabled()
  })
})

describe("scan upload form - a valid file", () => {
  it("proceeds through the existing upload flow, titled with its file name", async () => {
    await ready()
    const scan = fileOf("scan.pdf")

    choose(scan)
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    const args = mocks.uploadDocument.mock.calls[0]
    expect(args[0]).toBe(scan)
    expect(args[1]).toBe("t2")
    expect(args[2]).toBe("scan.pdf")
    expect(await screen.findByText(/Reviewing/)).toBeInTheDocument()
    expect(mocks.toastSuccess).toHaveBeenCalledWith("Document uploaded. Running OCR…")
  })

  it.each(["scan.TIF", "scan.tif", "scan.TIFF", "image.PNG", "photo.jpeg", "photo.JPG"])("accepts %s", async (name) => {
    await ready()

    choose(fileOf(name))

    await waitFor(() => expect(uploadButton()).toBeEnabled())
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it("a double extension with an allowed final one is let through - the server checks the content", async () => {
    await ready()

    choose(fileOf("document.exe.pdf"))

    await waitFor(() => expect(uploadButton()).toBeEnabled())
  })
})

describe("scan upload form - no size limit", () => {
  it("a file reporting 6 GB is accepted and uploaded", async () => {
    await ready()
    const huge = fileOf("huge.tiff", 6 * 1024 ** 3)

    choose(huge)
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledWith(huge, "t2", "huge.tiff", null, false, []))
  })
})

describe("scan upload form - the size line", () => {
  it("shows a human-readable size from the chosen file, with the exact byte count on hover", async () => {
    await ready()
    choose(fileOf("scan.pdf", 2_345_678))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    const size = await screen.findByText("2.2 MB")
    expect(size).toHaveAttribute("title", "2,345,678 bytes")
    expect(screen.queryByText(/2,345,678 bytes ·/)).not.toBeInTheDocument()
  })

  it("prefers the size the server recorded", async () => {
    mocks.uploadDocument.mockResolvedValue({ ...CLEAN_TICKET, sizeBytes: 5 * 1024 ** 3 })
    await ready()
    choose(fileOf("scan.pdf", 10))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    expect(await screen.findByText("5.0 GB")).toBeInTheDocument()
  })

  it("never shows NaN or undefined", async () => {
    mocks.uploadDocument.mockResolvedValue({ ...CLEAN_TICKET, sizeBytes: Number.NaN })
    await ready()
    choose(fileOf("scan.pdf", 10))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    await screen.findByText(/Reviewing/)
    expect(document.body.textContent).not.toMatch(/NaN|undefined/)
    expect(screen.getByText("unknown size")).toBeInTheDocument()
  })
})

describe("scan upload form - the server stays the authority", () => {
  it("shows the server's own 415 message for a file this form let through, as an upload error (not a validation error)", async () => {
    const message = "File type .pdf is not allowed: the content does not match the extension"
    mocks.uploadDocument.mockRejectedValue(new ApiError(415, message))
    await ready()
    choose(fileOf("fake.pdf"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(mocks.toastError).toHaveBeenCalledWith(message)
    // Not the client's field-level alert, and nothing was made up: the message is exactly the server's.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    expect(document.getElementById("file-error")).toBeNull()
  })

  it("shows a refusal from the malware gate unchanged", async () => {
    mocks.uploadDocument.mockResolvedValue({ ...CLEAN_TICKET, status: "quarantined", documentId: null, versionId: null, message: "Threat found: Eicar" })
    await ready()
    choose(fileOf("scan.pdf"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    expect(await screen.findByText("Threat found: Eicar")).toBeInTheDocument()
    expect(mocks.toastError).toHaveBeenCalledWith("Threat found: Eicar")
  })

  it("keeps the file after a server refusal so the person can see what was refused and try again", async () => {
    mocks.uploadDocument.mockRejectedValueOnce(new ApiError(500, "Upload failed."))
    await ready()
    choose(fileOf("scan.pdf"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await screen.findByText("Upload failed.")

    expect(uploadButton()).toBeEnabled()
  })
})

describe("scan upload form - polling and retry are unchanged", () => {
  async function uploadValidFile() {
    await ready()
    choose(fileOf("scan.pdf"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await screen.findByText(/Reviewing/)
  }

  it("polls the OCR status of the uploaded document", async () => {
    await uploadValidFile()

    await waitFor(() => expect(mocks.fetchOcrStatus).toHaveBeenCalledWith("d1"))
    expect(await screen.findByText("Processing OCR…")).toBeInTheDocument()
  })

  it("shows queued distinctly from processing", async () => {
    mocks.fetchOcrStatus.mockResolvedValue({ id: "o1", status: "queued", fields: {}, errorCode: null, friendlyError: null })

    await uploadValidFile()

    expect(await screen.findByText("Queued…")).toBeInTheDocument()
  })

  it("a completed OCR run shows Done", async () => {
    mocks.fetchOcrStatus.mockResolvedValue({ id: "o1", status: "completed", fields: {}, errorCode: null, friendlyError: null })

    await uploadValidFile()

    expect(await screen.findByText("Done")).toBeInTheDocument()
  })

  it("a failed OCR run offers Retry, and Retry re-runs OCR", async () => {
    // Retry re-runs the selected fields, so the type needs at least one OCR-extractable field.
    mocks.schemaFields = [
      {
        fieldId: "f1", fieldKey: "invoiceNumber", label: "Invoice number", helpText: null, dataType: "text", mandatory: true, hidden: false,
        ocrExtractable: true, displayOrder: 1, fieldGroup: null, defaultValue: null, options: [], validations: [],
      },
    ]
    mocks.fetchOcrStatus.mockResolvedValue({ id: "o1", status: "failed", fields: {}, errorCode: "TIMEOUT", friendlyError: "OCR timed out." })

    await uploadValidFile()
    fireEvent.click(await screen.findByRole("button", { name: "Retry" }))

    await waitFor(() => expect(mocks.rerunOcr).toHaveBeenCalledTimes(1))
  })

  it("a permanently unsupported format hides Retry", async () => {
    mocks.fetchOcrStatus.mockResolvedValue({ id: "o1", status: "failed", fields: {}, errorCode: "UNSUPPORTED_FORMAT", friendlyError: "Unsupported." })

    await uploadValidFile()

    await screen.findByText(/You can still fill in the fields manually\./)
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument()
  })

  it("'Upload another document' returns a blank form with no leftover validation message", async () => {
    await uploadValidFile()
    fireEvent.click(screen.getByRole("button", { name: /upload another document/i }))

    await waitFor(() => expect(uploadButton()).toBeDisabled())
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    expect(fileInput().value).toBe("")
  })
})