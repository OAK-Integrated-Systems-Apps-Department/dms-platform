"use client"

import { AdminBreadcrumb, type AdminBreadcrumbSegment } from "@/components/admin/admin-breadcrumb"
import { useFolders } from "@/lib/folders/use-folders"
import type { FolderNode } from "@/lib/folders/api"

/** Paths with more folders than this fold their middle into a "…" crumb, like the list's path bar. */
const MAX_FOLDERS = 3

/**
 * Where a document lives: Dashboard › My Documents › Finance › Invoices › its title. Each folder is a link
 * that opens My Documents at that folder (`/documents?folder=…`, the same address the list itself uses), so the
 * path on the document page is a way back up, not just a label.
 *
 * {@code trailing} is for a page UNDER the document (the online editor): the title then links back to the
 * document and {@code trailing} is the current page.
 *
 * The folders load after the document, so for a moment only "My Documents" shows; a folder the caller cannot
 * see simply ends the path early rather than failing.
 */
export function DocumentBreadcrumb({
  documentId,
  title,
  folderId,
  trailing,
  className,
}: {
  documentId: string
  title?: string
  folderId?: string | null
  trailing?: string
  className?: string
}) {
  const { byId } = useFolders()

  const chain: FolderNode[] = []
  let node = folderId ? byId.get(folderId) : undefined
  while (node && chain.length < 32) {
    chain.unshift(node)
    node = node.parentFolderId ? byId.get(node.parentFolderId) : undefined
  }

  const folderSegments: AdminBreadcrumbSegment[] =
    chain.length > MAX_FOLDERS
      ? [chain[0], null, ...chain.slice(-2)].map((folder) =>
          folder ? folderSegment(folder) : { label: "…" }
        )
      : chain.map(folderSegment)

  const segments: AdminBreadcrumbSegment[] = [
    { label: "Dashboard", href: "/" },
    { label: "My Documents", href: "/documents" },
    ...folderSegments,
    trailing
      ? { label: title ?? "Document", href: `/documents/${documentId}` }
      : { label: title ?? "Document" },
    ...(trailing ? [{ label: trailing }] : []),
  ]

  return <AdminBreadcrumb className={className} segments={segments} />
}

function folderSegment(folder: FolderNode): AdminBreadcrumbSegment {
  return { label: folder.name, href: `/documents?folder=${encodeURIComponent(folder.folderId)}` }
}
