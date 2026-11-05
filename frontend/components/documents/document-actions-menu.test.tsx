import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { DocumentActionsMenu } from "./document-actions-menu"

let granted: string[] = []

vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))
// The dialog itself (folder picker + move call) is exercised in the browser; here it only has to be asked for.
vi.mock("@/components/documents/move-document-dialog", () => ({
  MoveDocumentDialog: ({ documentId, currentFolderId }: { documentId: string; currentFolderId: string | null }) => (
    <div role="dialog">{`move ${documentId} from ${currentFolderId ?? "top"}`}</div>
  ),
}))

describe("DocumentActionsMenu", () => {
  beforeEach(() => {
    granted = []
  })

  it("is not shown at all to someone who may not move documents", () => {
    const { container } = render(<DocumentActionsMenu documentId="doc-1" title="Invoice 41" folderId={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it("offers Move to, and opens the move dialog for this document from where it is now", () => {
    granted = ["docm.documents.move"]
    render(<DocumentActionsMenu documentId="doc-1" title="Invoice 41" folderId="f-9" />)

    fireEvent.click(screen.getByRole("button", { name: /more actions for invoice 41/i }))
    fireEvent.click(screen.getByText("Move to"))

    expect(screen.getByRole("dialog")).toHaveTextContent("move doc-1 from f-9")
  })
})
