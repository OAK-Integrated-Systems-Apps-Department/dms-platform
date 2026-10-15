"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import { Lock } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { StageBuilder } from "@/components/workflow/stage-builder"
import { TemplateStatusActions } from "@/components/workflow/template-status-actions"
import { TemplateVersions } from "@/components/workflow/template-versions"
import { WorkflowTemplateDetailsForm } from "@/components/workflow/workflow-template-form"
import { ApiError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"
import { WORKFLOW_TEMPLATE_ANY_PERMISSION } from "@/lib/workflow/permissions"
import { useWorkflowTemplate } from "@/lib/workflow/use-workflow-template"
import {
  ESCALATION_TARGET_LABELS,
  ON_DOCUMENT_CHANGE_LABELS,
  TEMPLATE_STATUS_BADGE_VARIANT,
  TEMPLATE_STATUS_LABELS,
} from "@/lib/workflow/workflow-badges"

/**
 * One workflow template version (W17a): header + status actions, the
 * details (editable while a draft), the stage builder, and the family's
 * version list. Anything but a draft is read-only - "Create New Version"
 * copies it into a new draft and navigates there.
 */
export default function WorkflowTemplatePage() {
  const params = useParams<{ templateId: string }>()
  const router = useRouter()
  const workflow = useWorkflowTemplate(params.templateId)
  const { template, isPending, isError, error } = workflow
  const { hasPermission } = usePermissions()

  usePageTitle(template ? `${template.name} v${template.versionNumber}` : "Workflow Template")

  if (isError && error instanceof ApiError && error.status === 404) {
    notFound()
  }

  const isDraft = template?.status === "draft"
  const canEdit = isDraft && hasPermission(PermissionCodes.WA_TEMPLATES_UPDATE)

  async function handleCreateVersion() {
    const draft = await workflow.createVersion()
    if (draft) router.push(`/admin/workflows/${draft.templateId}`)
  }

  async function handleDelete() {
    if (await workflow.remove()) router.push("/admin/workflows")
  }

  return (
    <DashboardShell initialActiveLabel="Workflow Templates">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Workflow Templates", href: "/admin/workflows" },
          { label: template ? `${template.name} v${template.versionNumber}` : "Workflow Template" },
        ]}
      />

      {isPending ? (
        <LoadingState message="Loading workflow template…" />
      ) : (
        <PermissionGate permission={WORKFLOW_TEMPLATE_ANY_PERMISSION}>
          {isError || !template ? (
            <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
              {error instanceof Error ? error.message : "Could not load this workflow template."}
            </div>
          ) : (
            <>
              <div className="mb-[22px] flex flex-wrap items-start justify-between gap-4">
                <div className="max-w-[640px]">
                  <div className="mb-2 flex flex-wrap items-center gap-2.5">
                    <h1 className="font-heading text-[26px] font-semibold tracking-[-0.01em]">{template.name}</h1>
                    <Badge variant={TEMPLATE_STATUS_BADGE_VARIANT[template.status]} className="gap-1.5 rounded-md">
                      <span className="size-[5px] rounded-full bg-current" />
                      {TEMPLATE_STATUS_LABELS[template.status]}
                    </Badge>
                  </div>
                  <p className="text-[13px] text-muted-foreground">
                    <span className="font-mono text-[12.5px]">{template.code}</span> · Version {template.versionNumber} ·{" "}
                    {template.documentTypeName ?? "Unknown document type"}
                  </p>
                </div>
                <TemplateStatusActions
                  template={template}
                  busy={workflow.isSaving}
                  onActivate={() => void workflow.activate()}
                  onDeactivate={() => void workflow.deactivate()}
                  onDelete={() => void handleDelete()}
                  onCreateVersion={() => void handleCreateVersion()}
                />
              </div>

              {!isDraft && (
                <div className="mb-4 flex items-start gap-2 rounded-lg border border-border bg-muted px-4 py-3 text-[13px] text-foreground">
                  <Lock size={15} className="mt-0.5 shrink-0 text-muted-foreground" />
                  This version is {TEMPLATE_STATUS_LABELS[template.status].toLowerCase()} and read-only. Create a new
                  version to change its details or stages.
                </div>
              )}

              <div className="grid items-start gap-5 lg:grid-cols-[1.7fr_1fr]">
                <div className="flex min-w-0 flex-col gap-5">
                  <Panel title="Details">
                    <div className="pt-3">
                      {canEdit ? (
                        <WorkflowTemplateDetailsForm
                          key={template.templateId}
                          defaultValues={{
                            name: template.name,
                            description: template.description ?? "",
                            documentTypeId: template.documentTypeId,
                            onDocumentChange: template.onDocumentChange,
                            defaultReminderBeforeHours: template.defaultReminderBeforeHours,
                            defaultInactivityReminderHours: template.defaultInactivityReminderHours,
                            defaultEscalateAfterReminderHours: template.defaultEscalateAfterReminderHours,
                            defaultEscalationTarget: template.defaultEscalationTarget,
                            defaultEscalationUserId: template.defaultEscalationUserId,
                          }}
                          documentTypeLocked={template.versionNumber > 1}
                          onSubmit={async (values) =>
                            (await workflow.updateDetails({
                              name: values.name.trim(),
                              description: values.description.trim() || null,
                              documentTypeId: values.documentTypeId,
                              onDocumentChange: values.onDocumentChange,
                              defaultReminderBeforeHours: values.defaultReminderBeforeHours,
                              defaultInactivityReminderHours: values.defaultInactivityReminderHours,
                              defaultEscalateAfterReminderHours: values.defaultEscalateAfterReminderHours,
                              defaultEscalationTarget: values.defaultEscalationTarget,
                              defaultEscalationUserId:
                                values.defaultEscalationTarget === "named_user" ? values.defaultEscalationUserId : null,
                            })) !== null
                          }
                        />
                      ) : (
                        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
                          <Field label="Description" value={template.description || "—"} />
                          <Field label="Document Type" value={template.documentTypeName ?? "—"} />
                          <Field label="When The Document Changes" value={ON_DOCUMENT_CHANGE_LABELS[template.onDocumentChange]} />
                          <Field label="Due-Date Reminder Default" value={hoursLabel(template.defaultReminderBeforeHours, "before due")} />
                          <Field label="Inactivity Reminder Default" value={hoursLabel(template.defaultInactivityReminderHours, "without activity")} />
                          <Field label="Escalate After Reminder Default" value={hoursLabel(template.defaultEscalateAfterReminderHours, "after the inactivity reminder")} />
                          <Field label="Default Escalation Target" value={ESCALATION_TARGET_LABELS[template.defaultEscalationTarget]} />
                          <Field label="Used By A Workflow" value={template.hasInstances ? "Yes" : "No"} />
                          <Field label="Created" value={formatDateTime(template.createdAt)} />
                          <Field label="Updated" value={template.updatedAt ? formatDateTime(template.updatedAt) : "—"} />
                        </dl>
                      )}
                    </div>
                  </Panel>

                  <div>
                    <div className="mb-3 flex items-baseline justify-between">
                      <h2 className="font-heading text-[18px] font-semibold">Approval Stages</h2>
                      <span className="text-[12.5px] text-muted-foreground">Stages run one after another, top to bottom.</span>
                    </div>
                    <StageBuilder
                      template={template}
                      editable={canEdit}
                      busy={workflow.isSaving}
                      onAdd={workflow.addStage}
                      onUpdate={workflow.updateStage}
                      onDelete={workflow.deleteStage}
                      onReorder={workflow.reorderStages}
                    />
                  </div>
                </div>

                <Panel title="Versions">
                  <TemplateVersions templateId={template.templateId} />
                </Panel>
              </div>
            </>
          )}
        </PermissionGate>
      )}
    </DashboardShell>
  )
}

/** "48 hours without activity", or a dash when the template has no default. */
function hoursLabel(hours: number | null, what: string): string {
  return hours == null ? "—" : `${hours} ${hours === 1 ? "hour" : "hours"} ${what}`
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">{label}</dt>
      <dd className="mt-1 text-[13.5px] text-foreground">{value}</dd>
    </div>
  )
}
