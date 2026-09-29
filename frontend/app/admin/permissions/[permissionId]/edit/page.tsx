// "use client"

// import { notFound, useParams, useRouter } from "next/navigation"
// import { AlertTriangle, Loader2 } from "lucide-react"
// import { toast } from "sonner"

// import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
// import { DashboardShell } from "@/components/layout/dashboard-shell"
// import { PermissionForm } from "@/components/permissions/permission-form"
// import type { PermissionFormValues } from "@/components/permissions/schema"
// import { usePermission } from "@/components/permissions/use-permission"
// import { useIsPlatformAdmin } from "@/lib/auth/use-is-platform-admin"
// import { usePermissionsStore } from "@/stores/permissions-store"

// /**
//  * Edit Permission — update a permission's name, description, and
//  * module/action. Its code is fixed once created. PLATFORM_ADMIN-only (see
//  * `PermissionController`'s `@PreAuthorize`s) — a tenant admin landing here
//  * directly sees a "not authorized" message instead of the form, same
//  * reasoning as `AddPermissionPage`.
//  */
// export default function EditPermissionPage() {
//   const router = useRouter()
//   const params = useParams<{ permissionId: string }>()
//   const { permission, loading, notFound: permissionNotFound } = usePermission(
//     params.permissionId
//   )
//   const updatePermission = usePermissionsStore((state) => state.updatePermission)
//   const { isPlatformAdmin, isLoading: platformAdminLoading } = useIsPlatformAdmin()

//   if (permissionNotFound) {
//     notFound()
//   }

//   async function handleSubmit(values: PermissionFormValues): Promise<string | null> {
//     if (!permission) return "Permission not loaded yet."
//     const { error } = await updatePermission(permission.id, values)
//     if (error) {
//       toast.error(error)
//       return error
//     }
//     toast.success(`Permission "${values.name}" updated.`)
//     router.push(`/admin/permissions/${permission.id}`)
//     return null
//   }

//   const isLoading = loading || platformAdminLoading

//   return (
//     <DashboardShell initialActiveLabel="Permissions">
//       <AdminBreadcrumb
//         className="mb-3"
//         segments={[
//           { label: "Dashboard", href: "/" },
//           { label: "Permissions", href: "/admin/permissions" },
//           {
//             label: permission?.name ?? "Permission Details",
//             href: permission ? `/admin/permissions/${permission.id}` : undefined,
//           },
//           { label: "Edit" },
//         ]}
//       />
//       <div className="mb-[22px] max-w-[680px]">
//           <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
//             Edit Permission
//           </h1>
//           <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
//             {permission
//               ? <>Update {permission.name}&apos;s name, description, module, and action.</>
//               : "Update this permission's name, description, module, and action."}
//           </p>
//         </div>

//         {isLoading ? (
//           <div className="flex items-center justify-center gap-2 rounded-lg border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
//             <Loader2 size={16} className="animate-spin" />
//             Loading permission…
//           </div>
//         ) : !isPlatformAdmin ? (
//           <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
//             <AlertTriangle size={20} className="text-[var(--rust)]" />
//             Only a platform administrator can edit permissions.
//           </div>
//         ) : permission ? (
//           <PermissionForm mode="edit" permission={permission} onSubmit={handleSubmit} />
//         ) : (
//           <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
//             <AlertTriangle size={20} className="text-[var(--rust)]" />
//             Couldn&apos;t load this permission.
//           </div>
//         )}
//     </DashboardShell>
//   )
// }

// Placeholder while the page above is commented out: a page.tsx must export a
// default component, otherwise `next build` fails with "is not a module" and
// no production image can be built. This keeps the route as a plain 404 until
// the implementation above is restored - then delete these lines.
import { notFound } from "next/navigation"

export default function Page() {
  notFound()
}
