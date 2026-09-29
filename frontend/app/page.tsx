"use client"

import { useState } from "react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import {
  PendingApprovalsPanel,
  RecentActivityPanel,
  RecentDocumentsPanel,
} from "@/components/dashboard/panels"
import { StatsRow } from "@/components/dashboard/stats-row"
import { WelcomeHeader } from "@/components/dashboard/welcome-header"
import { recentDocuments } from "@/components/dashboard/data"
import type { DocumentFileType, RecentDocument } from "@/components/dashboard/types"
import { useCurrentUser } from "@/lib/auth/use-current-user"

function extensionToType(name: string): DocumentFileType {
  const ext = name.split(".").pop()?.toLowerCase()
  if (ext === "xlsx" || ext === "xls" || ext === "csv") return "xlsx"
  if (ext === "docx" || ext === "doc") return "docx"
  return "pdf"
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Home dashboard. The page itself only decides which panels appear and
 * where — comment out or delete any panel line below to remove it, or add
 * a new one the same way once you've built it.
 */
export default function Page() {
  const { data: user, isLoading: isUserLoading } = useCurrentUser()
  const [documents, setDocuments] = useState<RecentDocument[]>(recentDocuments)

  function handleUpload(files: FileList) {
    const uploaded: RecentDocument[] = Array.from(files).map((file, index) => ({
      id: `upload-${Date.now()}-${index}`,
      name: file.name,
      type: extensionToType(file.name),
      owner: "You",
      modified: "Just now",
      size: formatSize(file.size),
      status: "Pending",
    }))
    setDocuments((current) => [...uploaded, ...current])
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
        onUpload={handleUpload}
        onCreateFolder={handleCreateFolder}
      />

      <StatsRow />

      <div className="mb-[26px] grid items-start gap-5 lg:grid-cols-[1.6fr_1fr]">
        <RecentDocumentsPanel documents={documents} />
        <PendingApprovalsPanel />
      </div>

      <RecentActivityPanel />
    </DashboardShell>
  )
}