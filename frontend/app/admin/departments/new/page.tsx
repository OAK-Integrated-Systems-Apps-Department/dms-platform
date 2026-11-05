"use client"

import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { CreateDepartmentForm } from "@/components/departments/department-form"
import type { CreateDepartmentFormValues } from "@/components/departments/schema"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { createDepartment } from "@/lib/departments/api"
import { usePageTitle } from "@/lib/use-page-title"
import { workflowErrorMessage } from "@/lib/workflow/workflow-errors"

/** Add Department. */
export default function NewDepartmentPage() {
  usePageTitle("Add Department")
  const router = useRouter()
  const queryClient = useQueryClient()

  async function handleSubmit(values: CreateDepartmentFormValues): Promise<string | null> {
    try {
      const department = await createDepartment({
        name: values.name.trim(),
        code: values.code.trim(),
        parentDepartmentId: values.parentDepartmentId || null,
        headUserId: values.headUserId || null,
      })
      queryClient.invalidateQueries({ queryKey: ["departments"] })
      queryClient.invalidateQueries({ queryKey: ["lookup", "departments"] })
      toast.success(`Department "${department.name}" created.`)
      router.push("/admin/departments")
      return null
    } catch (err) {
      const message = workflowErrorMessage(err, "Could not create this department.")
      toast.error(message)
      return message
    }
  }

  return (
    <DashboardShell initialActiveLabel="Departments">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Departments", href: "/admin/departments" }, { label: "Add Department" }]}
      />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Add Department</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          The code can&apos;t be changed after creation. Members are assigned from each user&apos;s Edit User page.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.OTUA_DEPARTMENTS_CREATE}>
        <CreateDepartmentForm onSubmit={handleSubmit} />
      </PermissionGate>
    </DashboardShell>
  )
}
