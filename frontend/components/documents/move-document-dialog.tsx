"use client"

import { useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { FolderSelect } from "@/components/folders/folder-select"
import { ActionDialog } from "@/components/folders/folder-dialogs"
import { friendlyMessage } from "@/lib/api-error"
import { moveDocuments } from "@/lib/documents/api"
import { FOLDERS_KEY } from "@/lib/folders/use-folders"

/**
 * "Move to" for ONE document, from the document page - the same folder picker and the same move call as the
 * list's 3-dots menu. The server decides whether this caller may move it: a document that is checked out by
 * someone else, under legal hold or signed refuses with a message that is shown here and keeps the dialog open.
 */
export function MoveDocumentDialog({
  documentId,
  currentFolderId,
  onClose,
}: {
  documentId: string
  /** Where it is now (null = top level) - the picker starts there. */
  currentFolderId: string | null
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [target, setTarget] = useState(currentFolderId ?? "")

  async function move(): Promise<string | null> {
    try {
      await moveDocuments([documentId], target || null)
    } catch (error) {
      return friendlyMessage(error, "Could not move the document.")
    }
    // The page's breadcrumb and the list both read these.
    void queryClient.invalidateQueries({ queryKey: ["document", documentId] })
    void queryClient.invalidateQueries({ queryKey: ["documents"] })
    void queryClient.invalidateQueries({ queryKey: FOLDERS_KEY })
    toast.success("Document moved.")
    return null
  }

  return (
    <ActionDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Move document"
      description="Choose the folder to file it in."
      confirmLabel="Move"
      pendingLabel="Moving…"
      onConfirm={move}
    >
      <FolderSelect value={target} onChange={setTarget} noneLabel="No folder (top level)" className="w-full" inline />
    </ActionDialog>
  )
}
