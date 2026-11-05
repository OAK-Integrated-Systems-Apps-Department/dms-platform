"use client"

import { useRef, useState } from "react"
import Link from "next/link"
import { FilePenLine, Gavel, Loader2, Lock, LockOpen, PenLine, ShieldAlert } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Panel } from "@/components/ui/panel"
import { usePermissions } from "@/lib/auth/use-permissions"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCheckoutActions, useCheckoutStatus, useEditingConfig } from "@/lib/checkout/use-checkout"
import { formatDateTime } from "@/lib/utils"

import { LastEdited } from "./last-edited"
import { ReasonDialog } from "./reason-dialog"

const BUTTON =
  "flex items-center gap-1.5 border font-semibold disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] justify-center"
const PRIMARY = `${BUTTON} border-primary bg-primary text-primary-foreground`
const SECONDARY = `${BUTTON} border-[var(--border-strong)] bg-card text-foreground`

/**
 * 2.10 Check-in / Check-out: whether this document is available, checked out (by whom, since when),
 * under legal hold or signature-locked - visible to everyone who can open the document (CICO-FR-002,
 * CICO-NFR-007) - plus the actions the caller is allowed.
 *
 * The edit flow is the one the backend supports: Check out, download and edit the file yourself,
 * then Check in the edited file (it becomes a new version after the malware scan). Nothing here
 * touches signatures or watermarks: a signed document cannot be checked out, and a check-in only
 * ever adds a version.
 */
export function DocumentCheckoutPanel({ documentId, fileName }: { documentId: string; fileName?: string | null }) {
  const { data: status, isPending, isError } = useCheckoutStatus(documentId)
  const { data: editing } = useEditingConfig()
  const { checkOut, checkIn, discard, forceRelease, hold } = useCheckoutActions(documentId)
  const { hasPermission } = usePermissions()
  const fileInput = useRef<HTMLInputElement>(null)
  const [dialog, setDialog] = useState<"force" | "place-hold" | "lift-hold" | null>(null)

  if (isPending) return null
  if (isError || !status) {
    return (
      <Panel title="Editing lock">
        <p className="pt-3 text-[13px] text-muted-foreground">Could not load this document&apos;s lock status.</p>
      </Panel>
    )
  }

  const canCheckOut = hasPermission(PermissionCodes.CICO_CHECK_OUT)
  const canCheckIn = hasPermission(PermissionCodes.CICO_CHECK_IN)
  const canDiscard = hasPermission(PermissionCodes.CICO_DISCARD)
  const canForce = hasPermission(PermissionCodes.CICO_FORCE_RELEASE)
  const canPlaceHold = hasPermission(PermissionCodes.CRM_PLACE_LEGAL_HOLD)
  const canLiftHold = hasPermission(PermissionCodes.CRM_LIFT_LEGAL_HOLD)
  const busy = checkOut.isPending || checkIn.isPending || discard.isPending
  // "Edit online" (CICO-FR-006): only when the engine is up and edits this file type, and the caller may start a
  // session - a free document, or one they already hold. Anything else is refused by the backend with the reason.
  const extension = fileName?.includes(".") ? fileName.split(".").pop()?.toLowerCase() : undefined
  const canEditOnline =
    canCheckOut &&
    Boolean(editing?.enabled) &&
    Boolean(extension && editing?.extensions.includes(extension)) &&
    (status.state === "available" || (status.state === "checked_out" && status.checkedOutByMe))
  // Online editing is on, but not for THIS file type (a PDF, say): say so, and what to do instead, rather than
  // leaving the person wondering where the button went.
  const cannotEditInBrowser =
    Boolean(editing?.enabled) &&
    Boolean(extension) &&
    !editing?.extensions.includes(extension as string) &&
    status.state === "available"

  return (
    <Panel title="Editing lock">
      <div className="flex flex-col gap-3 pt-3">
        <div className="flex flex-wrap items-center gap-2 text-[13px]">
          {status.state === "available" && (
            <Badge variant="muted">
              <LockOpen aria-hidden="true" /> Available
            </Badge>
          )}
          {status.state === "checked_out" && (
            <Badge variant="gold">
              <Lock aria-hidden="true" /> Checked out
            </Badge>
          )}
          {status.state === "legal_hold" && (
            <Badge variant="rust">
              <Gavel aria-hidden="true" /> Legal hold
            </Badge>
          )}
          {status.state === "signature_locked" && (
            <Badge variant="rust">
              <ShieldAlert aria-hidden="true" /> Signed and locked
            </Badge>
          )}
          {status.state === "checked_out" && status.checkedOutAt && (
            <span className="text-muted-foreground">
              {status.checkedOutByMe ? "by you" : `by ${status.checkedOutByName ?? "another user"}`} since{" "}
              {formatDateTime(status.checkedOutAt)}
              {status.checkoutExpiresAt && <> · releases itself {formatDateTime(status.checkoutExpiresAt)}</>}
            </span>
          )}
        </div>

        <LastEdited status={status} />

        {status.legalHold && (
          <p className="text-[12.5px] text-muted-foreground">
            Under legal hold{status.legalHoldAt ? ` since ${formatDateTime(status.legalHoldAt)}` : ""}
            {status.legalHoldReason ? `: ${status.legalHoldReason}` : ""}. It cannot be checked out or changed.
          </p>
        )}
        {status.signatureLocked && !status.legalHold && (
          <p className="text-[12.5px] text-muted-foreground">
            This document carries a digital signature, so it can&apos;t be checked out or edited - changing it would
            break the signature. If it really has to change, remove the signature first (under Signatures).
          </p>
        )}
        {cannotEditInBrowser && (
          <p className="text-[12.5px] text-muted-foreground">
            This kind of file can&apos;t be edited in the browser. Check it out, edit the downloaded copy in your own
            program, then check the edited file in.
          </p>
        )}

        <div className="flex flex-wrap gap-2">
          {canEditOnline && (
            <Link href={`/documents/${documentId}/edit`} className={PRIMARY}>
              <FilePenLine size={14} aria-hidden="true" />
              Edit online
            </Link>
          )}
          {status.state === "available" && canCheckOut && (
            <button type="button" className={PRIMARY} disabled={busy} onClick={() => checkOut.mutate()}>
              {checkOut.isPending ? <Loader2 size={14} className="animate-spin" /> : <PenLine size={14} />}
              Check out
            </button>
          )}

          {status.state === "checked_out" && status.checkedOutByMe && (
            <>
              {canCheckIn && (
                <>
                  <input
                    ref={fileInput}
                    type="file"
                    hidden
                    onChange={(event) => {
                      const file = event.target.files?.[0]
                      event.target.value = ""
                      if (file) checkIn.mutate({ file })
                    }}
                  />
                  <button type="button" className={PRIMARY} disabled={busy} onClick={() => fileInput.current?.click()}>
                    {checkIn.isPending && <Loader2 size={14} className="animate-spin" />}
                    Check in edited file
                  </button>
                </>
              )}
              {canDiscard && (
                <button type="button" className={SECONDARY} disabled={busy} onClick={() => discard.mutate()}>
                  Discard check-out
                </button>
              )}
            </>
          )}

          {status.state === "checked_out" && !status.checkedOutByMe && canForce && (
            <button type="button" className={SECONDARY} onClick={() => setDialog("force")}>
              Force release
            </button>
          )}

          {!status.legalHold && canPlaceHold && (
            <button type="button" className={SECONDARY} onClick={() => setDialog("place-hold")}>
              Place legal hold
            </button>
          )}
          {status.legalHold && canLiftHold && (
            <button type="button" className={SECONDARY} onClick={() => setDialog("lift-hold")}>
              Lift legal hold
            </button>
          )}
        </div>
      </div>

      {dialog === "force" && (
        <ReasonDialog
          title="Release this check-out?"
          description={`${status.checkedOutByName ?? "The holder"} may lose edits they have not checked in. This is recorded in the audit trail.`}
          confirmLabel="Force release"
          onConfirm={(reason) => forceRelease.mutateAsync(reason)}
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}
      {(dialog === "place-hold" || dialog === "lift-hold") && (
        <ReasonDialog
          title={dialog === "place-hold" ? "Place a legal hold?" : "Lift the legal hold?"}
          description={
            dialog === "place-hold"
              ? "Nobody will be able to check this document out, add a version, restore, move or delete it until the hold is lifted."
              : "The document can be checked out and changed again."
          }
          confirmLabel={dialog === "place-hold" ? "Place hold" : "Lift hold"}
          onConfirm={(reason) => hold.mutateAsync({ place: dialog === "place-hold", reason })}
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}
    </Panel>
  )
}
