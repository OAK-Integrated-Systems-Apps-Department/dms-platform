"use client"

import { useState } from "react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import {
  PendingApprovalsPanel,
  RecentActivityPanel,
  RecentDocumentsPanel,
} from "@/components/dashboard/panels"
import { BulkUploadSheet } from "@/components/documents/bulk-upload-sheet"
import { FolderNameDialog } from "@/components/folders/folder-dialogs"
import { friendlyMessage } from "@/lib/api-error"
import { createFolder } from "@/lib/folders/api"
import { toast } from "sonner"
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
  const [upload, setUpload] = useState<{ key: number; files: File[] } | null>(
    null
  )

  // Real upload now: the picked files go to the bulk upload panel, which
  // sends each one through the malware-scanned POST /api/v1/documents.
  function handleUpload(files: FileList) {
    setUpload({ key: Date.now(), files: Array.from(files) })
  }

  const canCreateFolder = hasPermission(PermissionCodes.DSC_FOLDERS_CREATE)
  const [newFolder, setNewFolder] = useState<number | null>(null)

  // A real top-level folder in the shared tree; it also shows in the
  // (sample) recent list so the dashboard reacts straight away.
  async function handleCreateFolder(name: string): Promise<string | null> {
    try {
      const folder = await createFolder(name, null)
      const row: RecentDocument = {
        id: `folder-${folder.folderId}`,
        name: folder.name,
        type: "folder",
        owner: "You",
        modified: "Just now",
        size: "—",
        status: "Draft",
      }
      setDocuments((current) => [row, ...current])
      toast.success(`Created "${folder.name}".`, {
        description: "Open My Documents to add documents to it.",
      })
      return null
    } catch (error) {
      return friendlyMessage(error, "Could not create the folder.")
    }
  }

  return (
    <DashboardShell initialActiveLabel="Dashboard">
      <WelcomeHeader
        firstName={user?.displayName.split(" ")[0] ?? ""}
        dateLabel="Tuesday, August 18, 2026"
        isLoadingName={isUserLoading}
        onUpload={canUpload ? handleUpload : undefined}
        onCreateFolder={
          canCreateFolder ? () => setNewFolder(Date.now()) : undefined
        }
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
      {newFolder !== null && (
        <FolderNameDialog
          key={newFolder}
          open
          onOpenChange={(open) => !open && setNewFolder(null)}
          title="New folder"
          description="A top-level folder in your organisation's shared folders."
          confirmLabel="Create folder"
          onSubmit={handleCreateFolder}
        />
      )}
    </DashboardShell>
  )
}
