"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"
import { notFound, useParams, useRouter } from "next/navigation"
import { Trash2 } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DepartmentMembers } from "@/components/departments/department-members"
import { UpdateDepartmentForm } from "@/components/departments/department-form"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { ApiError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useDepartment } from "@/lib/departments/use-departments"
import { usePageTitle } from "@/lib/use-page-title"
import { DEPARTMENT_ANY_PERMISSION } from "@/lib/workflow/permissions"

/** Edit Department - name/parent/head/status, its direct members, and Delete (only once it has no members or children). */
export default function EditDepartmentPage() {
  const params = useParams<{ id: string }>()
  const router = useRouter()
  const { department, isPending, isError, error, members, membersLoading, update, remove, isDeleting } = useDepartment(params.id)
  const { hasPermission } = usePermissions()
  const canUpdate = hasPermission(PermissionCodes.OTUA_DEPARTMENTS_UPDATE)
  const canDelete = hasPermission(PermissionCodes.OTUA_DEPARTMENTS_DELETE)
  const [confirmDelete, setConfirmDelete] = useState(false)

  usePageTitle(department ? `Edit ${department.name}` : "Edit Department")

  if (isError && error instanceof ApiError && error.status === 404) {
    notFound()
  }

  async function handleDelete() {
    if (await remove()) router.push("/admin/departments")
  }

  return (
    <DashboardShell initialActiveLabel="Departments">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Departments", href: "/admin/departments" },
          { label: department?.name ?? "Department" },
        ]}
      />

      {isPending ? (
        <LoadingState message="Loading department…" />
      ) : (
        <PermissionGate permission={DEPARTMENT_ANY_PERMISSION}>
          {isError || !department ? (
            <LoadFailed error={error} fallback="Could not load this department." />
          ) : (
            <>
              <div className="mb-[22px] flex flex-wrap items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                  <div className="mb-2 flex items-center gap-2.5">
                    <h1 className="font-heading text-[26px] font-semibold tracking-[-0.01em]">{department.name}</h1>
                    <Badge variant={department.status === "active" ? "default" : "muted"}>
                      {department.status === "active" ? "Active" : "Inactive"}
                    </Badge>
                  </div>
                  <p className="text-[13px] text-muted-foreground">
                    <span className="font-mono text-[12.5px]">{department.code}</span> · {department.memberCount}{" "}
                    {department.memberCount === 1 ? "member" : "members"}
                  </p>
                </div>
                {canDelete && (
                  <button
                    type="button"
                    onClick={() => setConfirmDelete(true)}
                    disabled={isDeleting}
                    className="flex items-center gap-1.5 border border-[var(--rust)] bg-card text-[var(--rust)] disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
                  >
                    <Trash2 size={15} /> Delete
                  </button>
                )}
              </div>

              <div className="grid items-start gap-5 lg:grid-cols-[1.5fr_1fr]">
                {canUpdate ? (
                  <UpdateDepartmentForm
                    key={department.updatedAt ?? department.createdAt}
                    departmentId={department.departmentId}
                    headUserName={department.headUserName}
                    holdingOwnerName={department.holdingOwnerName}
                    defaultValues={{
                      name: department.name,
                      parentDepartmentId: department.parentDepartmentId ?? "",
                      headUserId: department.headUserId ?? "",
                      status: department.status,
                      holdingOwnerUserId: department.holdingOwnerUserId ?? "",
                    }}
                    onSubmit={(values) =>
                      update({
                        name: values.name.trim(),
                        parentDepartmentId: values.parentDepartmentId || null,
                        headUserId: values.headUserId || null,
                        status: values.status,
                        // Omitted keeps it; only an explicit clear removes it.
                        holdingOwnerUserId: values.holdingOwnerUserId || undefined,
                        clearHoldingOwner: !values.holdingOwnerUserId && Boolean(department.holdingOwnerUserId) ? true : undefined,
                      })
                    }
                  />
                ) : (
                  <Panel title="Details">
                    <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2">
                      <Field label="Parent" value={department.parentName ?? "—"} />
                      <Field label="Head" value={department.headUserName ?? "—"} />
                      <Field label="Holding owner" value={department.holdingOwnerName ?? "—"} />
                    </dl>
                  </Panel>
                )}

                <Panel title={`Members (${members.length})`}>
                  <DepartmentMembers members={members} loading={membersLoading} />
                </Panel>
              </div>
            </>
          )}
        </PermissionGate>
      )}

      {confirmDelete && department && (
        <AlertDialog open onOpenChange={(open) => !open && setConfirmDelete(false)}>
          <AlertDialogPortal>
            <AlertDialogPopup className="sm:max-w-md">
              <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
                <Trash2 size={20} aria-hidden="true" />
              </span>
              <AlertDialogTitle>Delete this department?</AlertDialogTitle>
              <AlertDialogDescription>
                <strong className="font-semibold text-foreground">{department.name}</strong> will be removed. A department
                can only be deleted once it has no members, no sub-departments, and no workflow stage routes to it.
              </AlertDialogDescription>
              <div className="flex items-center justify-center gap-2.5">
                <AlertDialogClose className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center">
                  Cancel
                </AlertDialogClose>
                <AlertDialogClose
                  onClick={() => void handleDelete()}
                  className="border border-[var(--rust)] bg-[var(--rust)] text-white h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
                >
                  Delete Department
                </AlertDialogClose>
              </div>
            </AlertDialogPopup>
          </AlertDialogPortal>
        </AlertDialog>
      )}
    </DashboardShell>
  )
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">{label}</dt>
      <dd className="mt-1 text-[13.5px] text-foreground">{value}</dd>
    </div>
  )
}
