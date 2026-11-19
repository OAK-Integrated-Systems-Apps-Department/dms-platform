import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { DocumentUploadForm } from "./document-upload-form"

/**
 * Which document type the Scan Documents form uploads with (FE-L4). The dropdown showed a default ("General") that the
 * Upload button and the upload itself did not know about, so a valid file could not be uploaded until the person had
 * touched the dropdown. Every test here drives the real form; only the API calls are replaced. The catalogue and the
 * schemas are controllable, so a catalogue that arrives late, changes on a refetch, or loses a type can be reproduced.
 */

type DocType = { documentTypeId: string; typeCode: string; typeName: string; status: "active" | "inactive" }
const GENERAL: DocType = { documentTypeId: "t-general", typeCode: "GENERAL", typeName: "General", status: "active" }
const INVOICE: DocType = { documentTypeId: "t-invoice", typeCode: "INVOICE", typeName: "Invoice", status: "active" }
const LETTER: DocType = { documentTypeId: "t-letter", typeCode: "LETTER", typeName: "Letter", status: "active" }

const field = (fieldKey: string) => ({
  fieldId: `f-${fieldKey}`, fieldKey, label: fieldKey, helpText: null, dataType: "text", mandatory: false, hidden: false,
  ocrExtractable: true, displayOrder: 1, fieldGroup: null, defaultValue: null, options: [], validations: [],
})

const mocks = vi.hoisted(() => ({
  types: [] as unknown[],
  typesGate: null as null | Promise<void>,
  schemaGate: {} as Record<string, Promise<void> | undefined>,
  uploadDocument: vi.fn(),
  fetchOcrStatus: vi.fn(),
  fetchSchema: vi.fn(),
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock("sonner", () => ({ toast: { error: mocks.toastError, success: mocks.toastSuccess } }))
vi.mock("@/components/folders/folder-select", () => ({ FolderSelect: () => <div data-testid="folder-select" /> }))
vi.mock("@/lib/document-types/api", () => ({
  fetchAllDocumentTypes: async () => {
    if (mocks.typesGate) await mocks.typesGate
    return mocks.types
  },
}))
vi.mock("@/lib/document-type-fields/api", () => ({
  fetchDocumentTypeSchema: async (id: string) => {
    mocks.fetchSchema(id)
    const gate = mocks.schemaGate[id]
    if (gate) await gate
    const fieldKey = { "t-general": "generalNumber", "t-invoice": "invoiceNumber", "t-letter": "letterRef" }[id] ?? "other"
    return { documentTypeId: id, typeCode: id, typeName: id, schemaVersion: 1, fields: [field(fieldKey)] }
  },
}))
vi.mock("@/lib/documents/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/documents/api")>()),
  uploadDocument: mocks.uploadDocument,
  fetchOcrStatus: mocks.fetchOcrStatus,
  fetchDocumentMetadata: async () => ({}),
  rerunOcr: async () => undefined,
  saveDocumentMetadata: async () => ({}),
}))

const CLEAN = {
  scanResultId: "s1", status: "clean", documentId: "d1", versionId: "v1", originalFilename: "scan.pdf", sizeBytes: null,
  threatName: null, message: null, retrying: false, submittedAt: "2026-01-01T00:00:00Z", completedAt: null,
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((r) => { resolve = r })
  return { promise, resolve }
}

let client: QueryClient
function renderForm() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <DocumentUploadForm />
    </QueryClientProvider>,
  )
}

/** The catalogue as the server now answers, and a refetch to pick it up. */
async function refetchCatalogue(types: DocType[]) {
  mocks.types = types
  await client.invalidateQueries({ queryKey: ["document-types"] })
}

const select = () => screen.getByLabelText("Document type") as HTMLSelectElement
const fileInput = () => document.getElementById("file") as HTMLInputElement
const uploadButton = () => screen.getByRole("button", { name: /^upload$/i }) as HTMLButtonElement
const choose = (name: string, size = 1500) => {
  const f = new File(["x"], name)
  Object.defineProperty(f, "size", { value: size })
  fireEvent.change(fileInput(), { target: { files: [f] } })
  return f
}
const pickType = (code: string) => fireEvent.change(select(), { target: { value: code } })
const sentTypeId = () => mocks.uploadDocument.mock.calls[0][1]
const sentFields = () => mocks.uploadDocument.mock.calls[0][5] as string[]

beforeEach(() => {
  vi.clearAllMocks()
  mocks.types = [GENERAL, INVOICE]
  mocks.typesGate = null
  mocks.schemaGate = {}
  mocks.uploadDocument.mockResolvedValue(CLEAN)
  mocks.fetchOcrStatus.mockResolvedValue({ id: "o1", status: "processing", fields: {}, errorCode: null, friendlyError: null })
})

describe("the default document type", () => {
  it("shows General", async () => {
    renderForm()

    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
  })

  it("a valid file enables Upload without touching the dropdown", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))

    choose("scan.pdf")

    await waitFor(() => expect(uploadButton()).toBeEnabled())
  })

  it("uploads with General's own id", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-general")
  })

  it("without a GENERAL type it uses the first active type, and says so in the dropdown", async () => {
    mocks.types = [INVOICE, LETTER]
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("INVOICE"))
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-invoice")
  })

  it("inactive types are never the default", async () => {
    mocks.types = [{ ...GENERAL, status: "inactive" as const }, INVOICE]
    renderForm()

    await waitFor(() => expect(select()).toHaveValue("INVOICE"))
  })
})

describe("the catalogue arriving late", () => {
  it("keeps Upload disabled, even with a valid file, until a type exists - then enables it with that type", async () => {
    const gate = deferred()
    mocks.typesGate = gate.promise
    renderForm()
    choose("scan.pdf")
    expect(uploadButton()).toBeDisabled()
    expect(screen.getByText("Loading…")).toBeInTheDocument()

    gate.resolve()

    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-general")
  })

  it("a catalogue that never loads leaves Upload disabled", async () => {
    mocks.typesGate = new Promise<void>(() => {})
    renderForm()

    choose("scan.pdf")

    expect(uploadButton()).toBeDisabled()
  })
})

describe("no usable document type", () => {
  it("an empty catalogue keeps Upload disabled and says why", async () => {
    mocks.types = []
    renderForm()

    await screen.findByText("No document types available")
    choose("scan.pdf")
    expect(select()).toBeDisabled()
    expect(uploadButton()).toBeDisabled()
  })

  it("a catalogue with only inactive types keeps Upload disabled", async () => {
    mocks.types = [{ ...GENERAL, status: "inactive" as const }, { ...INVOICE, status: "inactive" as const }]
    renderForm()

    await screen.findByText("No document types available")
    choose("scan.pdf")
    expect(uploadButton()).toBeDisabled()
  })
})

describe("an explicit choice", () => {
  it("overrides the preferred type, and the upload uses it", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))

    pickType("INVOICE")
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-invoice")
  })

  it("survives a refetch that still has it", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")

    await refetchCatalogue([GENERAL, INVOICE, LETTER])

    await waitFor(() => expect(select().options.length).toBe(3))
    expect(select()).toHaveValue("INVOICE")
  })

  it("is not overwritten when the preferred type changes (GENERAL arrives later)", async () => {
    mocks.types = [INVOICE, LETTER]
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("INVOICE"))
    pickType("LETTER")

    await refetchCatalogue([GENERAL, INVOICE, LETTER])

    await waitFor(() => expect(select().options.length).toBe(3))
    expect(select()).toHaveValue("LETTER")
  })

  it("falls back to the preferred type when the catalogue deactivates it - and the upload uses what the dropdown shows", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    await refetchCatalogue([GENERAL, { ...INVOICE, status: "inactive" as const }])

    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    expect(uploadButton()).toBeEnabled()
    fireEvent.click(uploadButton())
    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-general")
  })

  it("falls back to the preferred type when the catalogue removes it altogether", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    choose("scan.pdf")

    await refetchCatalogue([GENERAL, LETTER])

    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-general")
  })

  it("when everything is deactivated after a choice, Upload disables rather than sending a dead type", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    await refetchCatalogue([{ ...GENERAL, status: "inactive" as const }, { ...INVOICE, status: "inactive" as const }])

    await waitFor(() => expect(uploadButton()).toBeDisabled())
    expect(mocks.uploadDocument).not.toHaveBeenCalled()
  })

  it("a type deactivated and later active again is NOT silently re-selected (the choice was invalidated)", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    await refetchCatalogue([GENERAL, { ...INVOICE, status: "inactive" as const }])
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))

    await refetchCatalogue([GENERAL, INVOICE])

    await waitFor(() => expect(select().options.length).toBe(2))
    expect(select()).toHaveValue("GENERAL")
  })

  it("a type removed and later back in the catalogue is NOT silently re-selected", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    await refetchCatalogue([GENERAL, LETTER])
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))

    await refetchCatalogue([GENERAL, INVOICE, LETTER])

    await waitFor(() => expect(select().options.length).toBe(3))
    expect(select()).toHaveValue("GENERAL")
  })

  it("after reactivation the upload and OCR fields still follow the fallback type, not the old choice", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    await refetchCatalogue([GENERAL, { ...INVOICE, status: "inactive" as const }])
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    await refetchCatalogue([GENERAL, INVOICE])
    await waitFor(() => expect(select().options.length).toBe(2))
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-general")
  })

  it("the user can pick the reactivated type again explicitly", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    await refetchCatalogue([GENERAL, { ...INVOICE, status: "inactive" as const }])
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    await refetchCatalogue([GENERAL, INVOICE])
    await waitFor(() => expect(select().options.length).toBe(2))

    pickType("INVOICE")

    expect(select()).toHaveValue("INVOICE")
  })

  it("falls back to the first active type, not GENERAL, when GENERAL is absent", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    await refetchCatalogue([{ ...INVOICE, status: "inactive" as const }, LETTER])

    await waitFor(() => expect(select()).toHaveValue("LETTER"))
  })
})

describe("OCR fields follow the submitted type", () => {
  it("the default type's fields are the ones requested", async () => {
    renderForm()
    await waitFor(() => expect(mocks.fetchSchema).toHaveBeenCalledWith("t-general"))
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-general")
    expect(sentFields()).toEqual(["generalNumber"])
  })

  it("switching type requests the new type's fields and none of the previous type's", async () => {
    renderForm()
    await waitFor(() => expect(mocks.fetchSchema).toHaveBeenCalledWith("t-general"))
    pickType("INVOICE")
    await waitFor(() => expect(mocks.fetchSchema).toHaveBeenCalledWith("t-invoice"))
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentTypeId()).toBe("t-invoice")
    expect(sentFields()).toEqual(["invoiceNumber"])
    expect(sentFields()).not.toContain("generalNumber")
  })

  it("an upload cannot go out while the chosen type's schema is still loading (it would carry no fields)", async () => {
    const gate = deferred()
    mocks.schemaGate["t-invoice"] = gate.promise
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    choose("scan.pdf")

    await waitFor(() => expect(mocks.fetchSchema).toHaveBeenCalledWith("t-invoice"))
    expect(uploadButton()).toBeDisabled()

    gate.resolve()
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(sentFields()).toEqual(["invoiceNumber"])
  })

  it("the default type's schema also gates the upload while it loads", async () => {
    const gate = deferred()
    mocks.schemaGate["t-general"] = gate.promise
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    choose("scan.pdf")

    await waitFor(() => expect(mocks.fetchSchema).toHaveBeenCalledWith("t-general"))
    expect(uploadButton()).toBeDisabled()

    gate.resolve()
    await waitFor(() => expect(uploadButton()).toBeEnabled())
  })
})

describe("after an upload", () => {
  it("'Upload another document' keeps an explicit choice", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    pickType("INVOICE")
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await screen.findByText(/Reviewing/)

    fireEvent.click(screen.getByRole("button", { name: /upload another document/i }))

    await waitFor(() => expect(select()).toHaveValue("INVOICE"))
    expect(uploadButton()).toBeDisabled()
  })

  it("'Upload another document' with the untouched default still uploads with it", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    choose("scan.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())
    await screen.findByText(/Reviewing/)

    fireEvent.click(screen.getByRole("button", { name: /upload another document/i }))
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    choose("second.pdf")
    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(2))
    expect(mocks.uploadDocument.mock.calls[1][1]).toBe("t-general")
  })
})

describe("the file rules still apply with the default type", () => {
  it("an unsupported file is refused and nothing is sent", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))

    choose("setup.exe")

    expect(await screen.findByRole("alert")).toHaveTextContent(/not a supported file type/i)
    expect(uploadButton()).toBeDisabled()
    expect(mocks.uploadDocument).not.toHaveBeenCalled()
  })

  it("a zero-byte file is refused", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))

    choose("scan.pdf", 0)

    expect(await screen.findByRole("alert")).toHaveTextContent(/empty/i)
    expect(uploadButton()).toBeDisabled()
  })

  it("a very large supported file is accepted: there is no client-side size limit", async () => {
    renderForm()
    await waitFor(() => expect(select()).toHaveValue("GENERAL"))
    const huge = choose("huge.tiff", 6 * 1024 ** 3)

    await waitFor(() => expect(uploadButton()).toBeEnabled())
    fireEvent.click(uploadButton())

    await waitFor(() => expect(mocks.uploadDocument).toHaveBeenCalledTimes(1))
    expect(mocks.uploadDocument.mock.calls[0][0]).toBe(huge)
    expect(sentTypeId()).toBe("t-general")
  })
})