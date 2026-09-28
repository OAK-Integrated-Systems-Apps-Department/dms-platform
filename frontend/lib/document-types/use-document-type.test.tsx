import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, renderHook, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { describe, expect, it, vi } from "vitest"

import type { DocumentTypeFieldResponseDto } from "@/lib/document-type-fields/api"

import { useDocumentType } from "./use-document-type"

const api = vi.hoisted(() => ({
  fetchCurrentDocumentTypeFields: vi.fn(),
  replaceDocumentTypeFieldLabelAliases: vi.fn(),
  replaceDocumentTypeFields: vi.fn(),
}))
vi.mock("@/lib/document-type-fields/api", () => api)
vi.mock("./api", () => ({
  fetchDocumentType: vi.fn(async () => ({ documentTypeId: "dt-po", typeName: "Purchase Order" })),
  updateDocumentType: vi.fn(),
}))
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

function poNumber(labelAliases: string[]): DocumentTypeFieldResponseDto {
  return {
    documentTypeFieldId: "dtf-1",
    documentTypeId: "dt-po",
    fieldId: "f1",
    fieldKey: "po_number",
    label: "PO Number",
    schemaVersion: 3,
    status: "active",
    mandatory: false,
    hidden: false,
    requiresBackfill: false,
    displayOrder: 10,
    fieldGroup: null,
    defaultValueOverride: null,
    archivedAt: null,
    labelAliases,
  }
}

describe("useDocumentType — saveFieldLabelAliases", () => {
  it("a mapping refetch already in flight when the save lands can't overwrite the saved hints", async () => {
    let answerStaleFetch: (fields: DocumentTypeFieldResponseDto[]) => void = () => {}
    let answerFreshFetch: (fields: DocumentTypeFieldResponseDto[]) => void = () => {}
    api.fetchCurrentDocumentTypeFields
      .mockResolvedValueOnce([poNumber([])]) // initial load
      // e.g. a window-focus refetch, answered by dms-core before the PUT committed
      .mockImplementationOnce(() => new Promise((resolve) => (answerStaleFetch = resolve)))
      // anything started once the PUT has returned
      .mockImplementationOnce(() => new Promise((resolve) => (answerFreshFetch = resolve)))
    api.replaceDocumentTypeFieldLabelAliases.mockResolvedValue({ documentTypeId: "dt-po", fieldId: "f1", labelAliases: ["PO Number"] })

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    const { result } = renderHook(() => useDocumentType("dt-po"), { wrapper })
    await waitFor(() => expect(result.current.fields).toHaveLength(1))

    act(() => {
      void queryClient.invalidateQueries({ queryKey: ["document-type", "dt-po", "fields"] })
    })
    await waitFor(() => expect(api.fetchCurrentDocumentTypeFields).toHaveBeenCalledTimes(2))

    let message: string | null = "not called"
    await act(async () => {
      message = await result.current.saveFieldLabelAliases("f1", ["PO Number"])
    })
    expect(message).toBeNull()
    expect(api.replaceDocumentTypeFieldLabelAliases).toHaveBeenCalledWith("dt-po", "f1", ["PO Number"])
    // Shown straight away from the cache patch — the post-save refetch hasn't answered yet.
    await waitFor(() => expect(result.current.fields[0].labelAliases).toEqual(["PO Number"]))
    expect(api.fetchCurrentDocumentTypeFields).toHaveBeenCalledTimes(3)

    // The pre-PUT response finally arrives: it must be ignored.
    await act(async () => {
      answerStaleFetch([poNumber([])])
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
    expect(result.current.fields[0].labelAliases).toEqual(["PO Number"])

    await act(async () => answerFreshFetch([poNumber(["PO Number"])]))
    await waitFor(() => expect(queryClient.isFetching()).toBe(0))
    expect(result.current.fields[0].labelAliases).toEqual(["PO Number"])
  })
})
