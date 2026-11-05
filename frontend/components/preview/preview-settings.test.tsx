import { fireEvent, render, screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type {
  PreviewFailurePageDto,
  PreviewPolicyDto,
} from "@/lib/preview/api"

import { PreviewSettings } from "./preview-settings"

let policy: PreviewPolicyDto
let failures: PreviewFailurePageDto
let granted: string[] = []
const save = vi.fn()
const retry = vi.fn()

vi.mock("@/lib/preview/use-preview", () => ({
  usePreviewPolicy: () => ({ data: policy, isPending: false, isError: false }),
  useSavePreviewPolicy: () => ({ mutate: save, isPending: false }),
  usePreviewFailures: () => ({
    data: failures,
    isPending: false,
    isError: false,
  }),
  useRetryPreview: () => ({ mutate: retry, isPending: false }),
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({
    hasAnyPermission: (...codes: string[]) =>
      codes.some((code) => granted.includes(code)),
  }),
}))

const RESTRICT = "dprm.configure-preview-security-policy.restrict-print-download"

function radio(group: string, name: string) {
  return within(screen.getByRole("radiogroup", { name: group })).getByRole(
    "radio",
    { name }
  )
}

describe("PreviewSettings", () => {
  beforeEach(() => {
    save.mockClear()
    retry.mockClear()
    policy = { print: "allow", download: "allow", copy: "allow" }
    failures = {
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
      last: true,
    }
    granted = [RESTRICT]
  })

  it("shows what is allowed today, and offers no save until something changes", () => {
    render(<PreviewSettings />)

    expect(radio("Print", "Allow")).toHaveAttribute("aria-checked", "true")
    expect(radio("Download", "Allow")).toHaveAttribute("aria-checked", "true")
    expect(radio("Copy text", "Allow")).toHaveAttribute("aria-checked", "true")
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled()
    expect(screen.getByText(/Nothing is restricted/)).toBeTruthy()
  })

  it("saves exactly the three choices made", () => {
    render(<PreviewSettings />)

    fireEvent.click(radio("Print", "Watermark"))
    fireEvent.click(radio("Download", "Block"))
    fireEvent.click(radio("Copy text", "Block"))
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }))

    expect(save).toHaveBeenCalledWith({
      print: "watermark",
      download: "block",
      copy: "block",
    })
  })

  it("explains that a restricted preview is drawn as pictures, once anything is restricted", () => {
    render(<PreviewSettings />)

    fireEvent.click(radio("Download", "Watermark"))

    expect(screen.queryByText(/Nothing is restricted/)).toBeNull()
    expect(screen.getByText(/drawn on the server as pictures/)).toBeTruthy()
  })

  it("lets someone without the permission look, but not change", () => {
    granted = []
    render(<PreviewSettings />)

    expect(radio("Print", "Allow")).toBeDisabled()
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled()
  })

  it("can discard what was changed", () => {
    render(<PreviewSettings />)

    fireEvent.click(radio("Print", "Block"))
    fireEvent.click(screen.getByRole("button", { name: "Reset" }))

    expect(radio("Print", "Allow")).toHaveAttribute("aria-checked", "true")
    expect(save).not.toHaveBeenCalled()
  })

  it("says so when every preview has been made", () => {
    render(<PreviewSettings />)

    expect(screen.getByText(/nothing needs review/)).toBeTruthy()
  })

  it("lists the previews that failed, with why, and retries one", () => {
    failures = {
      ...failures,
      totalElements: 1,
      totalPages: 1,
      content: [
        {
          versionId: "v1",
          documentId: "d1",
          title: "Master agreement",
          fileName: "master.docx",
          versionNumber: 3,
          reason: "The converter did not return a PDF.",
          failedAt: "2026-11-05T10:00:00Z",
        },
      ],
    }
    render(<PreviewSettings />)

    expect(screen.getByRole("link", { name: "Master agreement" })).toHaveAttribute(
      "href",
      "/documents/d1"
    )
    expect(screen.getByText("The converter did not return a PDF.")).toBeTruthy()
    expect(screen.getByText(/master\.docx/)).toBeTruthy()
    fireEvent.click(screen.getByRole("button", { name: /Try again/ }))
    expect(retry).toHaveBeenCalledWith("v1")
  })
})
