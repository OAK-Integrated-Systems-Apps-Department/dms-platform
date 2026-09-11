// "use client"

// import { useRouter } from "next/navigation"
// import { AlertTriangle, Loader2 } from "lucide-react"
// import { toast } from "sonner"

// import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
// import { DashboardShell } from "@/components/layout/dashboard-shell"
// import { PermissionForm } from "@/components/permissions/permission-form"
// import type { PermissionFormValues } from "@/components/permissions/schema"
// import { useIsPlatformAdmin } from "@/lib/auth/use-is-platform-admin"
// import { usePermissionsStore } from "@/stores/permissions-store"

// /**
//  * Add Permission — create a new entry in the platform-wide permission
//  * catalog. Permission create/update/delete are PLATFORM_ADMIN-only (see
//  * `PermissionController`'s `@PreAuthorize`s): a permission invented at
//  * runtime has no matching `@PreAuthorize` check anywhere in the backend,
//  * so a tenant admin landing here directly (bookmark, typed URL) sees a
//  * "not authorized" message instead of the form — the backend would reject
//  * the submit either way, but there's no reason to render a form whose
//  * only possible outcome is a 403.
//  */
// export default function AddPermissionPage() {
//   const router = useRouter()
//   const addPermission = usePermissionsStore((state) => state.addPermission)
//   const { isPlatformAdmin, isLoading: platformAdminLoading } = useIsPlatformAdmin()

//   async function handleSubmit(values: PermissionFormValues): Promise<string | null> {
//     const { error } = await addPermission(values)
//     if (error) {
//       toast.error(error)
//       return error
//     }
//     toast.success(`Permission "${values.name}" created.`)
//     router.push("/admin/permissions")
//     return null
//   }

//   return (
//     <DashboardShell initialActiveLabel="Permissions">
//       <AdminBreadcrumb
//         className="mb-3"
//         segments={[
//           { label: "Dashboard", href: "/" },
//           { label: "Permissions", href: "/admin/permissions" },
//           { label: "Add Permission" },
//         ]}
//       />
//       <div className="mb-[22px] max-w-[680px]">
//           <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
//             Add Permission
//           </h1>
//           <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
//             Add a new grantable permission to the catalog, and choose the
//             module and action it belongs to. Every tenant's roles will be
//             able to draw on it once it's created.
//           </p>
//         </div>

//         {platformAdminLoading ? (
//           <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
//             <Loader2 size={16} className="animate-spin" />
//             Loading…
//           </div>
//         ) : isPlatformAdmin ? (
//           <PermissionForm mode="create" onSubmit={handleSubmit} />
//         ) : (
//           <div className="flex flex-col items-center gap-2 rounded-[14px] border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
//             <AlertTriangle size={20} className="text-[var(--rust)]" />
//             Only a platform administrator can add permissions to the catalog.
//           </div>
//         )}
//     </DashboardShell>
//   )
// }