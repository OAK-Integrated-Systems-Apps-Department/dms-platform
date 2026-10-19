"use client"

import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { CreateWorkflowTemplateForm } from "@/components/workflow/workflow-template-form"
import type { CreateTemplateFormValues } from "@/components/workflow/schema"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"
import { createWorkflowTemplate } from "@/lib/workflow/api"
import { workflowErrorMessage } from "@/lib/workflow/workflow-errors"

/** New Workflow Template (W17a) - creates version 1 as a draft, then opens its template page to add stages. */
export default function NewWorkflowTemplatePage() {
  usePageTitle("New Workflow Template")
  const router = useRouter()
  const queryClient = useQueryClient()

  async function handleSubmit(values: CreateTemplateFormValues): Promise<string | null> {
    try {
      const template = await createWorkflowTemplate({
        name: values.name.trim(),
        code: values.code.trim(),
        description: values.description.trim() || undefined,
        documentTypeId: values.documentTypeId,
        onDocumentChange: values.onDocumentChange,
        defaultInactivityReminderHours: values.defaultInactivityReminderHours,
        defaultEscalateAfterReminderHours: values.defaultEscalateAfterReminderHours,
      })
      queryClient.invalidateQueries({ queryKey: ["workflow", "templates"] })
      toast.success(`Template "${template.name}" created as a draft. Add its stages next.`)
      router.push(`/admin/workflows/${template.templateId}`)
      return null
    } catch (err) {
      const message = workflowErrorMessage(err, "Could not create this workflow template.")
      toast.error(message)
      return message
    }
  }

  return (
    <DashboardShell initialActiveLabel="Workflow Templates">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Workflow Templates", href: "/admin/workflows" }, { label: "New Template" }]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">New Workflow Template</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          The template starts as a draft (version 1). You&apos;ll add its approval stages on the next page, then activate
          it. The code can&apos;t be changed after creation.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.WA_TEMPLATES_CREATE}>
        <CreateWorkflowTemplateForm onSubmit={handleSubmit} />
      </PermissionGate>
    </DashboardShell>
  )
}
