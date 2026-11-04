"use client"

import { useState } from "react"
import { Gavel } from "lucide-react"

import { ReasonDialog } from "@/components/checkout/reason-dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import type { RetentionUnit, RetentionViewDto } from "@/lib/retention/api"
import { formatPeriod, formatPeriodText } from "@/lib/retention/format"
import { useChangeStatus, useDocumentRetention, useReviewActions } from "@/lib/retention/use-retention"
import { useErrorToast } from "@/lib/use-error-toast"
import { formatDateTime } from "@/lib/utils"

import { LifecycleBadge } from "./lifecycle-badge"

const BUTTON =
  "flex items-center gap-1.5 border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] justify-center"

type Dialog = null | "inactive" | "active" | "extend" | "dispose"

/**
 * A document's lifecycle on its page (CRM-FR-001, 002, 003, 012): its status, which retention rule applies and when it
 * will be archived and reviewed, what a legal hold is doing to all that, and the buttons the caller is allowed.
 * Everything the buttons can do is checked again by the server; this only stops offering what would be refused.
 */
export function DocumentRetentionPanel({ documentId }: { documentId: string }) {
  const { data, isPending, isError, error } = useDocumentRetention(documentId)
  useErrorToast(error, "Could not load this document's retention.")
  const { hasPermission } = usePermissions()
  const changeStatus = useChangeStatus()
  const { extend, dispose } = useReviewActions()
  const [dialog, setDialog] = useState<Dialog>(null)
  const [extendAmount, setExtendAmount] = useState("1")
  const [extendUnit, setExtendUnit] = useState<RetentionUnit>("years")

  if (isPending) return <LoadingState message="Loading status…" />
  if (isError || !data) {
    return <Panel title="Status & retention">{null}</Panel>
  }

  const canInactive = hasPermission(PermissionCodes.CRM_MARK_INACTIVE)
  const canActive = hasPermission(PermissionCodes.CRM_MARK_ACTIVE)
  const canReview = hasPermission(PermissionCodes.CRM_REVIEW_RETENTION)
  const held = data.legalHold
  const showInactive = data.lifecycleState === "active" && canInactive
  const showActive = data.lifecycleState !== "active" && canActive
  const amount = Number.parseInt(extendAmount, 10)
  const validAmount = Number.isFinite(amount) && amount >= 1 && amount <= 1000

  return (
    <>
      <Panel title="Status & retention">
        <div className="flex flex-col gap-3 pt-3 text-[13px]">
          <div className="flex flex-wrap items-center gap-2.5">
            <LifecycleBadge state={data.lifecycleState} archiveDueAt={data.archiveDueAt} />
            {data.changedAt && (
              <span className="text-[12.5px] text-muted-foreground">
                {data.changeSource === "retention" ? "Archived by the retention schedule" : `Changed by ${data.changedByName ?? "someone"}`}{" "}
                · {formatDateTime(data.changedAt)}
                {data.changeReason && data.changeSource === "manual" ? ` · “${data.changeReason}”` : ""}
              </span>
            )}
          </div>

          {held && (
            <p className="flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
              <Gavel size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
              <span>
                Under legal hold{data.legalHoldReason ? ` (${data.legalHoldReason})` : ""}. Its status is locked and
                automatic archiving is paused until the hold is lifted.
              </span>
            </p>
          )}

          {data.policyName ? (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-[12.5px]">
              <dt className="text-muted-foreground">Retention rule</dt>
              <dd className="font-medium">
                {data.policyName}
                {data.framework ? <span className="font-normal text-muted-foreground"> · {data.framework}</span> : null}
              </dd>
              <dt className="text-muted-foreground">Active for</dt>
              <dd>{formatPeriodText(data.activePeriod)}</dd>
              <dt className="text-muted-foreground">Then archived for</dt>
              <dd>{formatPeriodText(data.archivePeriod)}</dd>
              {data.archiveDueAt && data.lifecycleState !== "archived" && (
                <>
                  <dt className="text-muted-foreground">Archives on</dt>
                  <dd>{held ? `${formatDateTime(data.archiveDueAt)} (paused by the hold)` : formatDateTime(data.archiveDueAt)}</dd>
                </>
              )}
              {data.reviewDueAt && (
                <>
                  <dt className="text-muted-foreground">Reviewed on</dt>
                  <dd>{formatDateTime(data.reviewDueAt)}</dd>
                </>
              )}
              {data.extendedUntil && (
                <>
                  <dt className="text-muted-foreground">Extended until</dt>
                  <dd>{formatDateTime(data.extendedUntil)}</dd>
                </>
              )}
            </dl>
          ) : (
            <p className="text-[12.5px] text-muted-foreground">
              {data.needsPolicy
                ? "No retention rule covers this document yet. An administrator can assign one."
                : "No retention rule applies."}
            </p>
          )}

          {!data.automationEnabled && data.policyName && (
            <p className="text-[12.5px] text-muted-foreground">Automatic archiving is switched off for your organisation.</p>
          )}

          {(showInactive || showActive) && (
            <div className="flex flex-wrap gap-2">
              {showInactive && (
                <button type="button" className={BUTTON} disabled={held || changeStatus.isPending} onClick={() => setDialog("inactive")}>
                  Mark inactive
                </button>
              )}
              {showActive && (
                <button type="button" className={BUTTON} disabled={held || changeStatus.isPending} onClick={() => setDialog("active")}>
                  {data.lifecycleState === "archived" ? "Bring back to Active" : "Mark active"}
                </button>
              )}
            </div>
          )}

          {data.reviewFlaggedAt && (
            <ReviewBox
              data={data}
              canReview={canReview}
              amount={extendAmount}
              unit={extendUnit}
              onAmount={setExtendAmount}
              onUnit={setExtendUnit}
              validAmount={validAmount}
              onExtend={() => setDialog("extend")}
              onDispose={() => setDialog("dispose")}
            />
          )}
        </div>
      </Panel>

      {(dialog === "inactive" || dialog === "active") && (
        <ReasonDialog
          required={false}
          title={dialog === "inactive" ? "Mark this document inactive?" : "Mark this document active?"}
          description={
            dialog === "inactive"
              ? "It disappears from normal listings and search, and moves to Inactive & Archive for people allowed to see it. You can bring it back at any time."
              : "It returns to normal listings and search, and starts a fresh active period."
          }
          confirmLabel={dialog === "inactive" ? "Mark inactive" : "Mark active"}
          onConfirm={(reason) => changeStatus.mutateAsync({ documentId, status: dialog, reason })}
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}

      {dialog === "extend" && validAmount && (
        <ReasonDialog
          title="Keep this document longer"
          description={`It stays archived and its next review moves out by ${formatPeriod(amount, extendUnit)}. Say why - it is recorded in the audit trail.`}
          confirmLabel="Extend retention"
          onConfirm={(reason) => extend.mutateAsync({ documentId, amount, unit: extendUnit, reason })}
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}

      {dialog === "dispose" && (
        <ReasonDialog
          title="Send this document to the recycle bin?"
          description="Its retention has ended. It moves to the recycle bin, where it can still be restored until the recycle bin's own period ends. It is refused if the document is under legal hold, in an approval or linked to other documents."
          confirmLabel="Send to recycle bin"
          onConfirm={(reason) => dispose.mutateAsync({ documentId, reason })}
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}
    </>
  )
}

function ReviewBox({
  data,
  canReview,
  amount,
  unit,
  onAmount,
  onUnit,
  validAmount,
  onExtend,
  onDispose,
}: {
  data: RetentionViewDto
  canReview: boolean
  amount: string
  unit: RetentionUnit
  onAmount: (value: string) => void
  onUnit: (value: RetentionUnit) => void
  validAmount: boolean
  onExtend: () => void
  onDispose: () => void
}) {
  return (
    <div className="rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-3 text-[12.5px] text-[var(--gold-foreground)]">
      <p className="font-semibold">Retention period ended - waiting for an administrator</p>
      <p className="mt-0.5">
        Flagged on {formatDateTime(data.reviewFlaggedAt as string)}. Nothing has been deleted: choose whether to keep it
        longer or send it to the recycle bin.
      </p>
      {canReview ? (
        <div className="mt-2.5 flex flex-wrap items-end gap-2">
          <div>
            <Label htmlFor="extend-amount">Keep longer by</Label>
            <Input
              id="extend-amount"
              type="number"
              min={1}
              max={1000}
              value={amount}
              onChange={(e) => onAmount(e.target.value)}
              className="h-[38px] w-24 bg-card text-[13px]"
            />
          </div>
          <select
            aria-label="Extension unit"
            value={unit}
            onChange={(e) => onUnit(e.target.value as RetentionUnit)}
            className="h-[38px] rounded-btn border border-[var(--border-strong)] bg-card px-2.5 text-[13px] text-foreground"
          >
            <option value="days">days</option>
            <option value="months">months</option>
            <option value="years">years</option>
          </select>
          <button type="button" className={BUTTON} disabled={!validAmount} onClick={onExtend}>
            Keep longer…
          </button>
          <button type="button" className={BUTTON} onClick={onDispose}>
            Send to recycle bin…
          </button>
        </div>
      ) : (
        <p className="mt-1.5">Ask someone with the retention-review permission to decide.</p>
      )}
    </div>
  )
}
