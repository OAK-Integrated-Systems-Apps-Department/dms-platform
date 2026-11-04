"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import Link from "next/link"
import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { OUTCOME_BADGE_VARIANT, SEVERITY_BADGE_VARIANT } from "@/lib/audit-events/audit-badges"
import { useAuditEvent } from "@/lib/audit-events/use-audit-events"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { formatDateTime } from "@/lib/utils"

import type { AuditEventDetailResponseDto } from "@/lib/audit-events/api"

/** View Audit Event — read-only detail reached from a row on the Audit Log list. */
export default function ViewAuditEventPage() {
  const params = useParams<{ id: string }>()
  const { data: event, isPending, isError, error } = useAuditEvent(params.id)

  if (isError && error instanceof Error && error.message.includes("not found")) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Audit Log">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Audit Log", href: "/admin/audit-log" },
          { label: event ? event.action : "Audit Event" },
        ]}
      />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Audit Event
        </h1>
        <p className="max-w-[640px] text-[13.5px] leading-[1.6] text-muted-foreground">
          Full detail for one immutable audit record, including the before/after values captured
          at the time it was written.
        </p>
      </div>

      {isPending ? (
        <LoadingState message="Loading audit event…" />
      ) : (
        <PermissionGate permission={PermissionCodes.ABDR_VIEW_AUDIT_HISTORY}>
          {isError ? (
            <LoadFailed error={error} fallback="Could not load this audit event." />
          ) : event ? (
            <AuditEventDetail event={event} />
          ) : null}
        </PermissionGate>
      )}
    </DashboardShell>
  )
}

function AuditEventDetail({ event }: { event: AuditEventDetailResponseDto }) {
  return (
    <div className="flex flex-col gap-4">
      <Panel title="Event">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="Occurred At" value={formatDateTime(event.occurredAt)} mono />
          <Field label="Action" value={event.action} />
          <Field label="Module" value={event.module} />
          <Field
            label="Outcome"
            value={
              <Badge variant={OUTCOME_BADGE_VARIANT[event.outcome]} className="gap-1.5 rounded-md">
                <span className="size-[5px] rounded-full bg-current" />
                {event.outcome}
              </Badge>
            }
          />
          {event.severity && (
            <Field
              label="Severity"
              value={
                <Badge variant={SEVERITY_BADGE_VARIANT[event.severity]}>
                  {event.severity}
                </Badge>
              }
            />
          )}
          {event.failureReason && <Field label="Failure Reason" value={event.failureReason} />}
          <Field label="Reason" value={event.reason ?? "—"} />
          <Field label="Object Type" value={event.objectType} />
          {event.objectId && <Field label="Object ID" value={event.objectId} mono />}
          {event.resourceName && <Field label="Resource Name" value={event.resourceName} />}
          <Field label="Object Version" value={event.objectVersion != null ? String(event.objectVersion) : "—"} />
          <Field label="Permission Code" value={event.permissionCode ?? "—"} mono />
          <Field label="Data Classification" value={event.dataClassification ?? "—"} />
          <Field label="Legal Hold" value={event.legalHoldFlag == null ? "—" : event.legalHoldFlag ? "Yes" : "No"} />
        </dl>
      </Panel>

      <Panel title="Actor & Session">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="Actor" value={event.actorDisplayName ?? "—"} />
          <Field label="Actor Type" value={event.actorType} />
          {event.actorId && <Field label="Actor ID" value={event.actorId} mono />}
          <Field label="Impersonated By" value={event.impersonatedByDisplayName ?? "—"} />
          {event.impersonatedBy && (
            <Field label="Impersonated By (ID)" value={event.impersonatedBy} mono />
          )}
          {event.authMethod && <Field label="Auth Method" value={event.authMethod} />}
          <Field label="Session ID" value={event.sessionId ?? "—"} mono />
          {event.ipAddress && <Field label="IP Address" value={event.ipAddress} mono />}
          {event.clientType && <Field label="Client Type" value={event.clientType} />}
          {event.userAgent && <Field label="User Agent" value={event.userAgent} />}
        </dl>
      </Panel>

      {(event.previousValue != null || event.newValue != null) && (
        <Panel title="Before / After">
          <div className="grid grid-cols-1 gap-4 pt-3 lg:grid-cols-2">
            <JsonBlock label="Previous Value" value={event.previousValue} />
            <JsonBlock label="New Value" value={event.newValue} />
          </div>
        </Panel>
      )}

      <Panel title="Integrity & Traceability">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2">
          {event.correlationId && (
            <Field
              label="Part of operation"
              helper="Groups this event with other actions from the same operation."
              value={
                <Link
                  href={`/admin/audit-log?correlationId=${event.correlationId}`}
                  className="text-primary hover:underline"
                >
                  {event.correlationId}
                </Link>
              }
              mono
            />
          )}
          {event.requestId && (
            <Field
              label="System request"
              helper="Identifies the exact request that produced this event."
              value={event.requestId}
              mono
            />
          )}
          <Field
            label="Integrity checksum"
            helper="Confirms this record hasn't been altered since it was written."
            value={event.recordHash}
            mono
          />
          <Field
            label="Linked to prior record"
            helper="Chains this record to the one before it, so tampering with either would be detectable."
            value={event.previousHash ?? "— (first record in the chain)"}
            mono
          />
        </dl>
      </Panel>
    </div>
  )
}

function Field({
  label,
  helper,
  value,
  mono,
}: {
  label: string
  /** One-line plain-English explanation, rendered between the label and the value - for a field whose raw name/value alone (e.g. a hash or a UUID) wouldn't mean much to a non-technical reviewer. */
  helper?: string
  value: React.ReactNode
  mono?: boolean
}) {
  return (
    <div>
      <dt className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">{label}</dt>
      {helper && <p className="mt-0.5 text-[12px] text-muted-foreground">{helper}</p>}
      <dd className={`mt-1 text-[13.5px] text-foreground ${mono ? "font-mono text-[12.5px] break-all" : ""}`}>
        {value}
      </dd>
    </div>
  )
}

function JsonBlock({ label, value }: { label: string; value: unknown }) {
  return (
    <div>
      <p className="mb-1.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">{label}</p>
      <pre className="max-h-[320px] overflow-auto rounded-md border border-border bg-muted p-3 text-[12px] leading-[1.5] whitespace-pre-wrap text-foreground">
        {value != null ? JSON.stringify(value, null, 2) : "—"}
      </pre>
    </div>
  )
}
