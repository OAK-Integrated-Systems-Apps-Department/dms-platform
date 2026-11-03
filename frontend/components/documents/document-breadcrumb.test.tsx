import { render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import type { FolderNode } from "@/lib/folders/api"

import { DocumentBreadcrumb } from "./document-breadcrumb"

// folders: a (Finance) > b (Invoices) > c (2026) > d (Q1) > e (March)
const NAMES: Record<string, [string, string | null]> = {
  a: ["Finance", null],
  b: ["Invoices", "a"],
  c: ["2026", "b"],
  d: ["Q1", "c"],
  e: ["March", "d"],
}
const byId = new Map<string, FolderNode>(
  Object.entries(NAMES).map(([id, [name, parent]]) => [
    id,
    { folderId: id, name, parentFolderId: parent, children: [], path: name, depth: 0 } as unknown as FolderNode,
  ])
)

vi.mock("@/lib/folders/use-folders", () => ({ useFolders: () => ({ byId }) }))

describe("DocumentBreadcrumb", () => {
  it("shows the folder path in order, each folder opening My Documents at that folder", () => {
    render(<DocumentBreadcrumb documentId="doc-1" title="Invoice 41" folderId="b" />)

    expect(screen.getByRole("link", { name: "My Documents" })).toHaveAttribute("href", "/documents")
    expect(screen.getByRole("link", { name: "Finance" })).toHaveAttribute("href", "/documents?folder=a")
    expect(screen.getByRole("link", { name: "Invoices" })).toHaveAttribute("href", "/documents?folder=b")
    // The document itself is the current page: marked as such and not navigable (no href).
    expect(screen.getByText("Invoice 41")).toHaveAttribute("aria-current", "page")
    expect(screen.getByText("Invoice 41")).not.toHaveAttribute("href")

    const text = screen.getByRole("navigation").textContent ?? ""
    expect(text.indexOf("Finance")).toBeLessThan(text.indexOf("Invoices"))
    expect(text.indexOf("Invoices")).toBeLessThan(text.indexOf("Invoice 41"))
  })

  it("a top-level document has no folder crumbs", () => {
    render(<DocumentBreadcrumb documentId="doc-1" title="Loose file" folderId={null} />)
    expect(screen.getByRole("link", { name: "My Documents" })).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "Finance" })).not.toBeInTheDocument()
    expect(screen.getByText("Loose file")).toBeInTheDocument()
  })

  it("folds a long path to the first folder and the last two", () => {
    render(<DocumentBreadcrumb documentId="doc-1" title="Ledger" folderId="e" />)

    expect(screen.getByRole("link", { name: "Finance" })).toBeInTheDocument() // first
    expect(screen.getByRole("link", { name: "Q1" })).toBeInTheDocument() // last two
    expect(screen.getByRole("link", { name: "March" })).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "Invoices" })).not.toBeInTheDocument() // folded away
    expect(screen.queryByRole("link", { name: "2026" })).not.toBeInTheDocument()
    expect(screen.getAllByText("…").length).toBeGreaterThan(0)
  })

  it("under the online editor, the title links back to the document and the editor is the current page", () => {
    render(<DocumentBreadcrumb documentId="doc-1" title="Invoice 41" folderId="a" trailing="Edit online" />)

    expect(screen.getByRole("link", { name: "Invoice 41" })).toHaveAttribute("href", "/documents/doc-1")
    expect(screen.getByText("Edit online")).toHaveAttribute("aria-current", "page")
  })

  it("a folder the caller cannot see just ends the path early", () => {
    render(<DocumentBreadcrumb documentId="doc-1" title="Hidden" folderId="zzz" />)
    expect(screen.getByRole("link", { name: "My Documents" })).toBeInTheDocument()
    expect(screen.getByText("Hidden")).toBeInTheDocument()
  })
})
