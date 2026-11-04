"use client"

import { useState } from "react"
import { Eye, Loader2, Lock, PenLine, ShieldCheck, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Panel } from "@/components/ui/panel"
import { LoadingState } from "@/components/ui/loading-state"
import { usePermissions } from "@/lib/auth/use-permissions"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { type DocumentDto } from "@/lib/documents/api"
import { useDocumentPreview } from "@/components/workflow/use-document-preview"
import {
  useDocumentSignaturePlacementInfo,
  useDocumentSignatures,
  useSignatureActions,
} from "@/lib/signatures/use-signatures"
import { type DocumentSignatureDto, type SignatureStatus, type VerificationResult } from "@/lib/signatures/api"
import { formatDateTime } from "@/lib/utils"

import { SignDocumentDialog } from "./sign-document-dialog"
import { UnlockDocumentDialog } from "./unlock-document-dialog"

const STATUS_LABEL: Record<SignatureStatus, string> = {
  valid: "Valid",
  invalidated: "Invalidated",
}

const VERIFICATION_LABEL: Record<VerificationResult, string> = {
  valid: "Verified",
  signature_mismatch: "Signature mismatch",
  document_altered: "Document altered",
  unreadable: "Could not check (file unreadable)",
}

/**
 * 1.4 Digital Signature Integration: a document's signatures, newest first,
 * each naming its version and whether it's still the document's current
 * one - plus a locked banner (once an approved, validly-signed workflow has
 * locked the document - decision 6/7) with an Unlock action gated behind
 * `DSI_UNLOCK_SIGNED_DOCUMENTS`, a Verify-all button (DSI-FR-003), and - V118 -
 * a "Sign document" action for adding a standalone e-signature outside of
 * approving any workflow task, with its own "Remove signature" once one is
 * placed. No longer returns null for zero signatures: that was fine when
 * this panel was purely a read-only history, but someone who can sign now
 * needs somewhere to start even on a never-signed document.
 */
export function DocumentSignaturesPanel({
  document,
  workflowInProgress = false,
}: {
  document: DocumentDto
  /** Disables standalone sign/remove while true - the backend refuses both with a 409 anyway (guardNoActiveWorkflow), but surfacing that upfront saves the signer three dialog steps just to find out. */
  workflowInProgress?: boolean
}) {
  const { data: signatures, isPending, isError } = useDocumentSignatures(document.documentId)
  const { verifyAll, isVerifying, removeStandalone, isRemovingStandalone } = useSignatureActions()
  const { hasPermission } = usePermissions()
  const [unlocking, setUnlocking] = useState(false)
  const [signing, setSigning] = useState(false)
  const canSignStandalone =
    hasPermission(PermissionCodes.DSI_SIGN_DOCUMENT_STANDALONE) && hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE)
  const { data: placementInfo } = useDocumentSignaturePlacementInfo(canSignStandalone ? document.documentId : null)
  const preview = useDocumentPreview()

  if (isPending) {
    return (
      <Panel title="Signatures">
        <LoadingState message="Loading signatures…" />
      </Panel>
    )
  }
  if (isError || !signatures) {
    return (
      <Panel title="Signatures">
        <p className="pt-3 text-[13px] text-[var(--rust)]">Could not load this document&apos;s signatures.</p>
      </Panel>
    )
  }
  if (signatures.length === 0 && !canSignStandalone) {
    return null
  }

  const locked = Boolean(document.signatureLockedAt)
  const canUnlock = hasPermission(PermissionCodes.DSI_UNLOCK_SIGNED_DOCUMENTS)

  async function handleRemoveStandalone() {
    const ok = await removeStandalone(document.documentId)
    if (ok) {
      toast.success("Signature removed.")
    } else {
      toast.error("Could not remove the placed signature.")
    }
  }

  return (
    <Panel title="Signatures">
      {canSignStandalone && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <span className="text-[12.5px] text-muted-foreground">
            Add your own e-signature directly, outside of any approval.
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => preview.open(document)}
              className="flex items-center gap-1.5 border border-[var(--border-strong)] text-foreground hover:bg-muted h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
            >
              <Eye size={13} />
              View document
            </button>
            {placementInfo?.alreadyPlaced && (
              <button
                type="button"
                onClick={handleRemoveStandalone}
                disabled={isRemovingStandalone || workflowInProgress}
                title={workflowInProgress ? "This document has an approval workflow in progress." : undefined}
                className="flex items-center gap-1.5 border border-[var(--border-strong)] font-semibold text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px]"
              >
                {isRemovingStandalone ? <Loader2 size={13} className="animate-spin" /> : <Trash2 size={13} />}
                Remove signature
              </button>
            )}
            <button
              type="button"
              onClick={() => setSigning(true)}
              disabled={workflowInProgress}
              title={
                workflowInProgress
                  ? "This document has an approval workflow in progress - standalone signing isn't available until it finishes."
                  : undefined
              }
              className="flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
            >
              <PenLine size={13} />
              Sign document
            </button>
          </div>
        </div>
      )}

      {signatures.length === 0 && (
        <p className="pb-1 text-[13px] text-muted-foreground">No signatures on this document yet.</p>
      )}

      {locked && (
        <div className="mb-4 flex items-start justify-between gap-3 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2.5 text-[12.5px] text-[var(--gold-foreground)]">
          <div className="flex items-start gap-2">
            <Lock size={15} className="mt-0.5 shrink-0" aria-hidden="true" />
            <span>
              This document is locked - it carries a valid signature. New versions, restore, metadata edits, move and
              delete are refused until it is unlocked.
            </span>
          </div>
          {canUnlock && (
            <button
              type="button"
              onClick={() => setUnlocking(true)}
              className="shrink-0 whitespace-nowrap border border-[var(--gold-foreground)]/30 text-[var(--gold-foreground)] hover:bg-[var(--gold-foreground)]/10 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              Unlock
            </button>
          )}
        </div>
      )}

      {signatures.length > 0 && (
        <>
          <div className="mb-3 flex items-center justify-between">
            <span className="text-[12px] text-muted-foreground">
              {signatures.length} signature{signatures.length === 1 ? "" : "s"}
            </span>
            <button
              type="button"
              onClick={() => verifyAll(document.documentId)}
              disabled={isVerifying}
              className="flex items-center gap-1.5 border border-[var(--border-strong)] text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
            >
              {isVerifying ? <Loader2 size={13} className="animate-spin" /> : <ShieldCheck size={13} />}
              Verify all
            </button>
          </div>

          <ul className="divide-y divide-border">
            {signatures.map((signature) => (
              <SignatureRow key={signature.signatureId} signature={signature} />
            ))}
          </ul>
        </>
      )}

      {unlocking && <UnlockDocumentDialog documentId={document.documentId} onOpenChange={setUnlocking} />}
      {signing && <SignDocumentDialog document={document} onOpenChange={setSigning} />}
      {preview.element}
    </Panel>
  )
}

function SignatureRow({ signature }: { signature: DocumentSignatureDto }) {
  return (
    <li className="py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[13px] font-semibold text-foreground">{signature.signerName}</span>
        <Badge variant={signature.status === "valid" ? "default" : "rust"}>
          {STATUS_LABEL[signature.status]}
        </Badge>
        {!signature.currentVersion && <Badge variant="outline">Not the current version</Badge>}
        <span className="text-[12px] capitalize text-muted-foreground">{signature.meaning}</span>
      </div>
      <p className="mt-0.5 text-[12px] text-muted-foreground">
        Version {signature.versionNumber} · Signed {formatDateTime(signature.signedAt)}
      </p>
      {signature.comment && <p className="mt-1 text-[12.5px] text-foreground">{signature.comment}</p>}
      {signature.status === "invalidated" && signature.statusReason && (
        <p className="mt-1 text-[12px] text-[var(--rust)]">{signature.statusReason}</p>
      )}
      {signature.visualSignatureKind === "typed" && signature.visualSignatureText && (
        <p className="mt-1.5 text-[22px] font-signature text-foreground">{signature.visualSignatureText}</p>
      )}
      {signature.visualSignatureKind !== "typed" && signature.visualSignatureImageBase64 && (
        // eslint-disable-next-line @next/next/no-img-element -- a small inline base64 PNG, not worth next/image's overhead
        <img
          src={`data:image/png;base64,${signature.visualSignatureImageBase64}`}
          alt={`${signature.signerName}'s signature`}
          className="mt-1.5 h-10 w-auto"
        />
      )}
      {signature.lastVerificationResult && (
        <p className="mt-1 text-[11.5px] text-muted-foreground">
          Last verified {signature.lastVerifiedAt ? formatDateTime(signature.lastVerifiedAt) : "—"}:{" "}
          {VERIFICATION_LABEL[signature.lastVerificationResult]}
        </p>
      )}
    </li>
  )
}
