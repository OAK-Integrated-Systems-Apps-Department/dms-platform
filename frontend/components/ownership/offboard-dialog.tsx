"use client"

import { useState } from "react"
import Link from "next/link"
import { useQueryClient } from "@tanstack/react-query"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { Checkbox } from "@/components/ui/checkbox"
import { LoadingState } from "@/components/ui/loading-state"
import { Textarea } from "@/components/ui/textarea"
import { PersonPicker } from "@/components/workflow/person-picker"
import { friendlyMessage } from "@/lib/api-error"
import { confirmationFlagsOf, MAX_TRANSFER_DOCUMENTS, offboardUser, transferBulk } from "@/lib/ownership/api"
import { useOffboardingPreview } from "@/lib/ownership/use-ownership"
import { lookupPeople } from "@/lib/workflow/api"

import type { OffboardResponseDto, OffboardingPreviewDto } from "@/lib/ownership/types"
import type { PersonOptionDto } from "@/lib/workflow/api"

const REASON_MAX_LENGTH = 1000

const HOLDING_SOURCES: Record<string, string> = {
  holding_owner: "the department's holding owner",
  department_head: "the head of the department",
  tenant_admin: "the tenant administrator",
}

const BLOCKER_TEXT: Record<string, string> = {
  ALREADY_DEACTIVATED: "This account is already deactivated.",
  TENANT_ADMIN_ACCOUNT: "The tenant administrator account can't be offboarded.",
  MISSING_PERMISSION: "You need both the bulk transfer and the user deactivation permissions.",
}

interface OffboardDialogProps {
  user: { userId: string; name: string }
  open: boolean
  onOpenChange: (open: boolean) => void
  onDone?: (response: OffboardResponseDto) => void
}

/**
 * Offboard a user: move everything they own (to a person, or parked with the department's holding owner) and
 * then deactivate the account. Open approval tasks they hold are listed, never reassigned - the admin confirms
 * how many will stay stuck. More than 5,000 documents are moved in steps first. Remount per opening.
 */
export function OffboardDialog({ user, open, onOpenChange, onDone }: OffboardDialogProps) {
  const queryClient = useQueryClient()
  const preview = useOffboardingPreview(user.userId, open)
  const [mode, setMode] = useState<"transfer" | "holding">("transfer")
  const [person, setPerson] = useState<PersonOptionDto | null>(null)
  const [grantAccess, setGrantAccess] = useState(true)
  const [reason, setReason] = useState("")
  const [acknowledged, setAcknowledged] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)

  const data = preview.data
  const stuck = data?.flags.stuckTaskCount ?? 0
  const blocked = !data || !data.canOffboard
  const ready =
    !blocked && reason.trim().length > 0 && (stuck === 0 || acknowledged) && (mode === "holding" || person !== null)

  async function submit(): Promise<string | null> {
    if (!data || !ready) return "Fill in the reason and confirm the open tasks."
    const target =
      mode === "holding"
        ? ({ type: "holding" } as const)
        : ({ type: "user", newOwnerUserId: person?.userId } as const)
    try {
      // Above 5,000 documents the transfer is done in steps first; the offboarding then moves the rest.
      let remaining = data.visibleCount
      while (remaining > MAX_TRANSFER_DOCUMENTS) {
        setProgress(`Moving documents… ${(data.visibleCount - remaining).toLocaleString()} of ${data.visibleCount.toLocaleString()}`)
        const step = await transferBulk({
          sourceUserId: user.userId,
          mode: "all",
          limit: MAX_TRANSFER_DOCUMENTS,
          target,
          grantAccess,
        })
        if (step.transferredCount === 0) break
        remaining -= step.transferredCount
      }
      setProgress("Offboarding…")
      const response = await offboardUser(user.userId, {
        reason: reason.trim(),
        mode,
        newOwnerUserId: mode === "transfer" ? person?.userId : undefined,
        grantAccess,
        acknowledgedStuckTasks: stuck,
      })
      void queryClient.invalidateQueries()
      onDone?.(response)
      return null
    } catch (error) {
      const changed = confirmationFlagsOf(error)
      if (changed) {
        // The work moved since the dialog opened: show the fresh numbers and ask again.
        setAcknowledged(false)
        void preview.refetch()
        return `The number of open tasks changed (now ${changed.stuckTaskCount}). Check the list and confirm again.`
      }
      return friendlyMessage(error, "Could not offboard the user.")
    } finally {
      setProgress(null)
    }
  }

  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Offboard ${user.name}`}
      description="Their documents are moved first, then the account is deactivated. This can't be done in half."
      confirmLabel="Offboard user"
      pendingLabel={progress ?? "Offboarding…"}
      onConfirm={submit}
      disabled={!ready}
    >
      {preview.isPending ? (
        <LoadingState message="Checking what this involves…" />
      ) : preview.isError || !data ? (
        <p role="alert" className="text-[12.5px] text-[var(--rust)]">
          {friendlyMessage(preview.error, "Could not load what offboarding this user involves.")}
        </p>
      ) : (
        <div className="flex max-h-[60vh] flex-col gap-3.5 overflow-y-auto pr-1">
          {data.blockers.length > 0 && (
            <ul role="alert" className="rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
              {data.blockers.map((b) => (
                <li key={b}>{BLOCKER_TEXT[b] ?? b}</li>
              ))}
            </ul>
          )}
          <OwnedSummary data={data} />

          <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
            What happens to their documents
            <select
              aria-label="What happens to their documents"
              value={mode}
              onChange={(e) => setMode(e.target.value as "transfer" | "holding")}
              className="rounded-btn border border-[var(--border-strong)] bg-card px-3 py-2 text-[13px] font-normal"
            >
              <option value="transfer">Transfer them to a person</option>
              <option value="holding">Park them with a holding owner</option>
            </select>
          </label>
          {mode === "transfer" ? (
            <div className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
              New owner
              <PersonPicker
                queryKey={["offboard", user.userId]}
                fetchPeople={(search) => lookupPeople(search, 20)}
                value={person}
                onChange={setPerson}
                placeholder="Search for the new owner"
              />
            </div>
          ) : (
            <p className="rounded-btn border border-border bg-muted px-3 py-2 text-[12.5px] text-muted-foreground">
              {data.suggestedTarget
                ? `They will be held by ${data.suggestedTarget.name} (${HOLDING_SOURCES[data.suggestedTarget.source] ?? data.suggestedTarget.source}) until someone is chosen. The administrators are told.`
                : "Nobody is available to hold them: set a holding owner for the department first."}
            </p>
          )}
          <Checkbox
            label="Also give the new owner access"
            hint="If they couldn't open a document, they are given Can manage on it."
            checked={grantAccess}
            onCheckedChange={setGrantAccess}
          />

          <FlaggedWork data={data} />
          {stuck > 0 && (
            <Checkbox
              label={`I understand ${stuck} ${stuck === 1 ? "task" : "tasks"} will stay stuck`}
              hint="Open approval tasks are not reassigned automatically. Reassign them from the links above."
              checked={acknowledged}
              onCheckedChange={setAcknowledged}
            />
          )}

          <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
            Reason
            <Textarea
              aria-label="Reason"
              rows={2}
              maxLength={REASON_MAX_LENGTH}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why is this account being deactivated?"
              className="min-h-[60px] font-normal"
            />
          </label>
        </div>
      )}
    </ActionDialog>
  )
}

function OwnedSummary({ data }: { data: OffboardingPreviewDto }) {
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-1.5 rounded-btn border border-border bg-muted px-3.5 py-2.5 text-[12.5px]">
      <div className="flex justify-between gap-3">
        <dt className="text-muted-foreground">Documents owned</dt>
        <dd className="font-semibold">{data.ownedTotal.toLocaleString()}</dd>
      </div>
      <div className="flex justify-between gap-3">
        <dt className="text-muted-foreground">In the Recycle Bin</dt>
        <dd className="font-semibold">{data.inRecycleBinCount.toLocaleString()}</dd>
      </div>
      {data.hiddenCount > 0 && (
        <p className="col-span-2 text-muted-foreground">
          {data.hiddenCount.toLocaleString()} more {data.hiddenCount === 1 ? "document" : "documents"} can only be
          transferred by a tenant administrator, and stay with the deactivated account until then.
        </p>
      )}
      {data.visibleCount > MAX_TRANSFER_DOCUMENTS && (
        <p className="col-span-2 text-muted-foreground">
          That is more than {MAX_TRANSFER_DOCUMENTS.toLocaleString()} documents, so they are moved in steps first.
        </p>
      )}
    </dl>
  )
}

function FlaggedWork({ data }: { data: OffboardingPreviewDto }) {
  const { openTasks, startedRuns } = data.flags
  if (openTasks.length === 0 && startedRuns.length === 0) return null
  return (
    <div className="flex flex-col gap-2.5 text-[12.5px]">
      {openTasks.length > 0 && (
        <div>
          <p className="mb-1 font-semibold">Open approval tasks they hold ({openTasks.length})</p>
          <ul aria-label="Open approval tasks" className="divide-y divide-border rounded-btn border border-border">
            {openTasks.map((task) => (
              <li key={task.taskId} className="flex items-center justify-between gap-3 px-3 py-2">
                <span className="min-w-0 truncate">
                  {task.documentTitle ?? "A document you can't open"}
                  {task.stageName && <span className="text-muted-foreground"> · {task.stageName}</span>}
                  <span className="text-muted-foreground"> · {task.route}</span>
                </span>
                <span className="flex shrink-0 gap-3">
                  {task.documentUrl && task.canReassign && (
                    <Link href={task.documentUrl} className="font-semibold text-primary hover:underline">
                      Reassign
                    </Link>
                  )}
                  <Link href={task.instanceUrl} className="font-semibold text-primary hover:underline">
                    Open run
                  </Link>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {startedRuns.length > 0 && (
        <div>
          <p className="mb-1 font-semibold">Approvals they started ({startedRuns.length})</p>
          <p className="mb-1 text-muted-foreground">
            A run can&apos;t change hands; open it to cancel it if it should not continue.
          </p>
          <ul aria-label="Approvals they started" className="divide-y divide-border rounded-btn border border-border">
            {startedRuns.map((run) => (
              <li key={run.instanceId} className="flex items-center justify-between gap-3 px-3 py-2">
                <span className="min-w-0 truncate">{run.documentTitle ?? "A document you can't open"}</span>
                <Link href={run.instanceUrl} className="shrink-0 font-semibold text-primary hover:underline">
                  Open run
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
