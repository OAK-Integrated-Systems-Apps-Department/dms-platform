"use client"

import { useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { TransferDialog } from "@/components/ownership/transfer-dialog"
import { Badge } from "@/components/ui/badge"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useDocumentOwnership } from "@/lib/ownership/use-ownership"

interface OwnerFieldProps {
  documentId: string
  title: string
}

/**
 * The document page's "Owner" entry (a dt/dd pair for the Document panel's list): the owner, their department,
 * a Holding badge while the document waits for a new owner, and a Transfer button for people who may move it.
 * Shows nothing if the owner can't be loaded.
 */
export function OwnerField({ documentId, title }: OwnerFieldProps) {
  const { hasPermission } = usePermissions()
  const queryClient = useQueryClient()
  const { data } = useDocumentOwnership(documentId)
  const [transferring, setTransferring] = useState(false)

  if (!data) return null
  const canTransfer = data.canTransfer && hasPermission(PermissionCodes.DOT_TRANSFER_INITIATE)
  const gone = data.owner?.status === "deactivated" || data.owner?.status === null

  return (
    <div>
      <dt className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">Owner</dt>
      <dd className="mt-1 flex flex-wrap items-center gap-2 text-[13.5px] text-foreground">
        <span>
          {data.owner?.name ?? "—"}
          {data.ownerDepartment && <span className="text-muted-foreground"> · {data.ownerDepartment.name}</span>}
        </span>
        {data.ownershipState === "holding" && (
          <Badge variant="gold" title="Parked with a holding owner until someone is chosen to take it over">
            Holding
          </Badge>
        )}
        {gone && (
          <Badge variant="muted" title="The owner's account is no longer active">
            Deactivated
          </Badge>
        )}
        {canTransfer && (
          <button
            type="button"
            onClick={() => setTransferring(true)}
            className="rounded-btn border border-[var(--border-strong)] bg-card px-2.5 py-1 text-[12px] font-semibold text-foreground hover:border-primary hover:text-primary"
          >
            Transfer
          </button>
        )}
      </dd>
      {transferring && (
        <TransferDialog
          subject={{ kind: "document", documentId, title }}
          open={transferring}
          onOpenChange={setTransferring}
          onDone={(outcome) => {
            void queryClient.invalidateQueries({ queryKey: ["ownership"] })
            void queryClient.invalidateQueries({ queryKey: ["document"] })
            if (outcome.kind === "document" && outcome.result.accessConvertedToRestricted) {
              toast.success("Ownership transferred. The document was switched to Restricted so the new owner can open it.")
            } else {
              toast.success("Ownership transferred.")
            }
          }}
        />
      )}
    </div>
  )
}
