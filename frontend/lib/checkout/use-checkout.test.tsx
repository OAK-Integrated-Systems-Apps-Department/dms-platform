import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, renderHook, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"

const toastSuccess = vi.fn()
const toastError = vi.fn()
vi.mock("sonner", () => ({
  toast: { success: (m: string) => toastSuccess(m), error: (m: string) => toastError(m) },
}))

const checkInDocument = vi.fn()
vi.mock("./api", () => ({
  checkInDocument: (...args: unknown[]) => checkInDocument(...args),
  checkOutDocument: vi.fn(),
  discardCheckout: vi.fn(),
  fetchCheckoutStatus: vi.fn(),
  fetchEditingConfig: vi.fn(),
  fetchEditingSettings: vi.fn(),
  fetchUserPreferences: vi.fn(),
  forceReleaseCheckout: vi.fn(),
  liftLegalHold: vi.fn(),
  placeLegalHold: vi.fn(),
  saveEditingSettings: vi.fn(),
  saveUserPreferences: vi.fn(),
}))

import { useCheckoutActions } from "./use-checkout"

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

const ticket = (status: string) => ({
  scanResultId: "scan-1",
  status,
  documentId: "doc-1",
  versionId: null,
  originalFilename: "contract.docx",
  sizeBytes: 3,
  threatName: null,
  message: null,
  retrying: false,
  submittedAt: "2026-11-11T00:00:00Z",
  completedAt: null,
})

async function checkIn(status: string) {
  checkInDocument.mockResolvedValueOnce(ticket(status))
  const { result } = renderHook(() => useCheckoutActions("doc-1"), { wrapper })
  await act(async () => {
    result.current.checkIn.mutate({ file: new File(["x"], "contract.docx") })
  })
  await waitFor(() => expect(result.current.checkIn.isSuccess).toBe(true))
}

describe("check-in toast (DOC-07)", () => {
  beforeEach(() => {
    toastSuccess.mockReset()
    toastError.mockReset()
    checkInDocument.mockReset()
  })

  it("says the check-out stays while the scan is pending, and does not claim a new version exists", async () => {
    await checkIn("pending")
    expect(toastSuccess).toHaveBeenCalledTimes(1)
    const message = toastSuccess.mock.calls[0][0] as string
    expect(message).toBe(
      "Check-in submitted. Your file is being scanned, and the document remains checked out until processing completes or the checkout is released."
    )
    expect(message).not.toMatch(/Checked in\./)
    expect(message).not.toMatch(/new version/i)
  })

  it("keeps the existing message when the check-in completed straight away", async () => {
    await checkIn("clean")
    expect(toastSuccess).toHaveBeenCalledWith(
      "Checked in. Your file is being scanned and becomes the new version when it is clean."
    )
  })

  it("keeps the existing error behaviour when the check-in is refused", async () => {
    checkInDocument.mockRejectedValueOnce(new Error("refused"))
    const { result } = renderHook(() => useCheckoutActions("doc-1"), { wrapper })
    await act(async () => {
      result.current.checkIn.mutate({ file: new File(["x"], "contract.docx") })
    })
    await waitFor(() => expect(result.current.checkIn.isError).toBe(true))
    expect(toastError).toHaveBeenCalledTimes(1)
    expect(toastSuccess).not.toHaveBeenCalled()
  })
})
