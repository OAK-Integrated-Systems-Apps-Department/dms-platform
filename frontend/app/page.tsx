"use client"

import { useState } from "react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import {
  PendingApprovalsPanel,
  RecentActivityPanel,
  RecentDocumentsPanel,
} from "@/components/dashboard/panels"
import { BulkUploadSheet } from "@/components/documents/bulk-upload-sheet"
import { StatsRow } from "@/components/dashboard/stats-row"
import { WelcomeHeader } from "@/components/dashboard/welcome-header"
import { recentDocuments } from "@/components/dashboard/data"
import type { RecentDocument } from "@/components/dashboard/types"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { usePermissions } from "@/lib/auth/use-permissions"

/**
 * Home dashboard. The page itself only decides which panels appear and
 * where — comment out or delete any panel line below to remove it, or add
 * a new one the same way once you've built it.
 */
export default function Page() {
  const { data: user, isLoading: isUserLoading } = useCurrentUser()
  const [documents, setDocuments] = useState<RecentDocument[]>(recentDocuments)
  const { hasPermission } = usePermissions()
  const canUpload = hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE)
  // Each opening gets a fresh sheet (new key) seeded with the picked files.
  const [upload, setUpload] = useState<{ key: number; files: File[] } | null>(null)

  // Real upload now: the picked files go to the bulk upload panel, which
  // sends each one through the malware-scanned POST /api/v1/documents.
  function handleUpload(files: FileList) {
    setUpload({ key: Date.now(), files: Array.from(files) })
  }

  function handleCreateFolder(name: string) {
    const folder: RecentDocument = {
      id: `folder-${Date.now()}`,
      name,
      type: "folder",
      owner: "You",
      modified: "Just now",
      size: "—",
      status: "Draft",
    }
    setDocuments((current) => [folder, ...current])
  }

  return (
    <DashboardShell initialActiveLabel="Dashboard">
      <WelcomeHeader
        firstName={user?.displayName.split(" ")[0] ?? ""}
        dateLabel="Tuesday, August 18, 2026"
        isLoadingName={isUserLoading}
        onUpload={canUpload ? handleUpload : undefined}
        onCreateFolder={handleCreateFolder}
      />

      <StatsRow />

      <div className="mb-[26px] grid items-start gap-5 lg:grid-cols-[1.6fr_1fr]">
        <RecentDocumentsPanel documents={documents} />
        <PendingApprovalsPanel />
      </div>

      <RecentActivityPanel />

      {upload && (
        <BulkUploadSheet
          key={upload.key}
          open
          initialFiles={upload.files}
          onOpenChange={(open) => {
            if (!open) setUpload(null)
          }}
        />
      )}
    </DashboardShell>
  )
}