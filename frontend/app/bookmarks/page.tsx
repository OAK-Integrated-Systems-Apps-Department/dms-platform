"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { BookmarksView } from "@/components/bookmarks/bookmarks-view"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** My Bookmarks (Module BD): the signed-in user's own private document shortcuts. */
export default function MyBookmarksPage() {
  usePageTitle("My Bookmarks")
  return (
    <DashboardShell initialActiveLabel="My Bookmarks">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "My Bookmarks" }]}
      />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          My Bookmarks
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Private shortcuts only you can see. Bookmark a document from My Documents, its preview, its
          own page, search results or Shared with me, and find it again here.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.DOCM_DOCUMENTS_VIEW}>
        <BookmarksView />
      </PermissionGate>
    </DashboardShell>
  )
}
