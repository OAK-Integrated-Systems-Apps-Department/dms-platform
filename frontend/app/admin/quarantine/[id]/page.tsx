"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"
import { notFound, useParams } from "next/navigation"
import { Loader2 } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { QuarantineActionDialog, type QuarantineAction } from "@/components/quarantine/quarantine-action-dialog"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  QUARANTINE_STATUS_BADGE_VARIANT,
  QUARANTINE_STATUS_LABELS,
  RELEASE_ATTEMPT_BADGE_VARIANT,
  RELEASE_ATTEMPT_LABELS,
  formatBytes,
} from "@/lib/quarantine/quarantine-badges"
import { QUARANTINE_ANY_PERMISSION } from "@/lib/quarantine/permissions"
import { useQuarantineRecord } from "@/lib/quarantine/use-quarantine-record"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"

import type { QuarantineRecordDetailDto } from "@/lib/quarantine/api"

/**
 * Quarantined file detail (VMSBDI FR-005) - the quarantine record, the scan
 * that caught it, its release history, and the review actions:
 * Release (re-scan, releases only if now clean), Override (force-release a
 * file the scanner still flags - only after a refused re-scan, separate
 * permission) and Delete (permanent). Every action needs a reason.
 * The quarantined bytes are never downloadable or previewable here.
 */
export default function QuarantineDetailPage() {
  usePageTitle("Quarantined File")
  const params = useParams<{ id: string }>()
  const { data: record, isPending, isError, error, release, overrideRelease, remove, isActing } =
    useQuarantineRecord(params.id)
  const { hasPermission } = usePermissions()
  const [dialog, setDialog] = useState<QuarantineAction | null>(null)

  if (isError && error instanceof Error && error.message.includes("not found")) {
    notFound()
  }

  const held = record?.status === "held"
  const canRelease = held && !record?.releaseInProgress && hasPermission(PermissionCodes.DOCM_QUARANTINE_RELEASE)
  const canOverride = Boolean(record?.overrideAvailable) && hasPermission(PermissionCodes.DOCM_QUARANTINE_OVERRIDE)
  const canDelete = held && !record?.releaseInProgress && hasPermission(PermissionCodes.DOCM_QUARANTINE_DELETE)

  function handleConfirm(reason: string) {
    if (dialog === "release") release(reason)
    else if (dialog === "override") overrideRelease(reason)
    else if (dialog === "delete") remove(reason)
  }

  return (
    <DashboardShell initialActiveLabel="Quarantine">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Quarantine", href: "/admin/quarantine" },
          { label: record ? record.originalFilename : "Quarantined File" },
        ]}
      />
      <div className="mb-[22px] flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Quarantined File</h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            A file the malware scan blocked before it reached the repository, with the scan result that caught
            it. The file itself cannot be opened or downloaded.
          </p>
        </div>
        {record && (
          <div className="flex flex-wrap items-center gap-2.5">
            {isActing && <Loader2 size={16} className="animate-spin text-muted-foreground" aria-label="Working" />}
            {canRelease && (
              <ActionButton onClick={() => setDialog("release")} disabled={isActing} className="border-primary bg-primary text-primary-foreground">
                Release…
              </ActionButton>
            )}
            {canOverride && (
              <ActionButton onClick={() => setDialog("override")} disabled={isActing} className="border-[var(--gold-foreground)] bg-card text-[var(--gold-foreground)]">
                Override…
              </ActionButton>
            )}
            {canDelete && (
              <ActionButton onClick={() => setDialog("delete")} disabled={isActing} className="border-[var(--rust)] bg-card text-[var(--rust)]">
                Delete…
              </ActionButton>
            )}
          </div>
        )}
      </div>

      {isPending ? (
        <LoadingState message="Loading quarantined file…" />
      ) : (
        <PermissionGate permission={QUARANTINE_ANY_PERMISSION}>
          {isError ? (
            <LoadFailed error={error} fallback="Could not load this quarantined file." />
          ) : record ? (
            <QuarantineDetail record={record} />
          ) : null}
        </PermissionGate>
      )}

      {record && dialog && (
        <QuarantineActionDialog
          action={dialog}
          filename={record.originalFilename}
          open={dialog !== null}
          onOpenChange={(open) => !open && setDialog(null)}
          onConfirm={handleConfirm}
        />
      )}
    </DashboardShell>
  )
}

function QuarantineDetail({ record }: { record: QuarantineRecordDetailDto }) {
  const scan = record.scan
  return (
    <div className="flex flex-col gap-4">
      {record.releaseInProgress && (
        <div className="flex items-center gap-2 rounded-lg border border-border bg-muted px-4 py-3 text-[13px] text-foreground">
          <Loader2 size={15} className="animate-spin text-muted-foreground" />
          A re-scan release is in progress. This page updates automatically when the verdict arrives.
        </div>
      )}

      <Panel title="Quarantine Record">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="File" value={record.originalFilename} />
          <Field label="Threat" value={record.threatName} />
          <Field
            label="Status"
            value={
              <Badge variant={QUARANTINE_STATUS_BADGE_VARIANT[record.status]} className="gap-1.5 rounded-btn">
                <span className="size-[5px] rounded-full bg-current" />
                {QUARANTINE_STATUS_LABELS[record.status]}
              </Badge>
            }
          />
          <Field label="Detected" value={formatDateTime(record.detectedAt)} mono />
          <Field label="Uploaded By" value={record.uploadedBy?.displayName ?? "—"} />
          <Field label="Size" value={formatBytes(record.sizeBytes)} />
          <Field label="SHA-256" value={record.fileHash} mono />
        </dl>
      </Panel>

      <Panel title="Scan Result">
        {scan ? (
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2 lg:grid-cols-3">
            <Field label="Engine" value={scan.engineName ?? "—"} />
            <Field label="Engine Version" value={scan.engineVersion ?? "— (not exposed by this provider)"} />
            <Field label="Signature Version" value={scan.signatureVersion ?? "— (not exposed by this provider)"} />
            <Field label="Scanned At" value={scan.scannedAt ? formatDateTime(scan.scannedAt) : "—"} mono />
            <Field label="Scan Duration" value={scan.durationMs != null ? `${scan.durationMs} ms` : "—"} />
            <Field label="Scan Mode" value={scan.scanMode === "pre_store_stream" ? "Stream (synchronous)" : "Object (asynchronous)"} />
            <Field label="File Type" value={scan.mimeType} mono />
          </dl>
        ) : (
          <p className="pt-3 text-[13px] text-muted-foreground">The original scan result is not available.</p>
        )}
      </Panel>

      {(record.reviewedBy || record.reviewReason) && (
        <Panel title="Review">
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2 lg:grid-cols-3">
            <Field label="Reviewed By" value={record.reviewedBy?.displayName ?? "—"} />
            <Field label="Reviewed At" value={record.reviewedAt ? formatDateTime(record.reviewedAt) : "—"} mono />
            <Field label="Reason" value={record.reviewReason ?? "—"} />
            {record.releasedDocumentId && (
              // Plain id, not a link - there is no document screen in the frontend yet.
              <Field label="Released As Document" value={record.releasedDocumentId} mono />
            )}
          </dl>
        </Panel>
      )}

      <Panel title="Release History">
        {record.releaseAttempts.length === 0 ? (
          <p className="pt-3 text-[13px] text-muted-foreground">No release has been attempted.</p>
        ) : (
          <div className="overflow-x-auto pt-3">
            <table className="tbl-card w-full min-w-[760px] border-collapse">
              <thead>
                <tr className="border-b border-border">
                  {["Requested", "Requested By", "Outcome", "Reason", "Detail"].map((h) => (
                    <th key={h} className="px-3 py-2 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {record.releaseAttempts.map((attempt) => (
                  <tr key={attempt.scanResultId} className="border-b border-border last:border-b-0">
                    <td className="px-3 py-2.5 font-mono text-[12.5px] text-muted-foreground">{formatDateTime(attempt.requestedAt)}</td>
                    <td className="px-3 py-2.5 text-[13px]">{attempt.requestedBy?.displayName ?? "—"}</td>
                    <td className="px-3 py-2.5">
                      <Badge variant={RELEASE_ATTEMPT_BADGE_VARIANT[attempt.status]}>{RELEASE_ATTEMPT_LABELS[attempt.status]}</Badge>
                    </td>
                    <td className="px-3 py-2.5 text-[13px]">{attempt.reason ?? "—"}</td>
                    <td className="px-3 py-2.5 text-[12.5px] text-muted-foreground">
                      {attempt.status === "quarantined"
                        ? `Still detected: ${attempt.threatName ?? "unknown"}${attempt.signatureVersion ? ` (signatures ${attempt.signatureVersion})` : ""}`
                        : attempt.failureReason ?? (attempt.signatureVersion ? `Signatures ${attempt.signatureVersion}` : "—")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </div>
  )
}

function ActionButton({
  onClick,
  disabled,
  className,
  children,
}: {
  onClick: () => void
  disabled: boolean
  className: string
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`rounded-btn border px-4 py-2 text-[13px] font-bold disabled:cursor-not-allowed disabled:opacity-60 ${className}`}
    >
      {children}
    </button>
  )
}

function Field({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">{label}</dt>
      <dd className={`mt-1 text-[13.5px] text-foreground ${mono ? "font-mono text-[12.5px] break-all" : ""}`}>{value}</dd>
    </div>
  )
}
