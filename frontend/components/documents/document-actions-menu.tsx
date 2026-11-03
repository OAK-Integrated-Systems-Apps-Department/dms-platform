"use client"

import { useState } from "react"
import { FolderInput } from "lucide-react"

import { ItemMenu, type ItemAction } from "@/components/documents/browser-items"
import { MoveDocumentDialog } from "@/components/documents/move-document-dialog"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"

/**
 * The 3-dots menu on the document page header - the same "Move to" the My Documents list offers on each
 * document, so a document can be re-filed from where you are reading it. Offered only to someone with the move
 * permission (the list uses the same one); whether THIS document may move (edit access, not locked, not on
 * legal hold) is the server's call and its reason is shown in the dialog. Renders nothing when there is
 * nothing to offer, so the header never shows an empty menu.
 */
export function DocumentActionsMenu({
  documentId,
  title,
  folderId,
}: {
  documentId: string
  title: string
  folderId: string | null
}) {
  const { hasPermission } = usePermissions()
  const [moving, setMoving] = useState(false)

  const actions: ItemAction[] = []
  if (hasPermission(PermissionCodes.DOCM_DOCUMENTS_MOVE)) {
    actions.push({ label: "Move to", icon: FolderInput, onSelect: () => setMoving(true) })
  }
  if (actions.length === 0) return null

  return (
    <>
      <ItemMenu label={title} actions={actions} />
      {moving && (
        <MoveDocumentDialog documentId={documentId} currentFolderId={folderId} onClose={() => setMoving(false)} />
      )}
    </>
  )
}
