"use client"

import type { CSSProperties } from "react"
import Link from "next/link"
import type { LucideIcon } from "lucide-react"
import { Download, Eye, FileText, FolderOpen, Lock } from "lucide-react"

import {
  DashCard,
  DashSkeleton,
  EmptyState,
  SectionError,
} from "@/components/dashboard/dash-card"
import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { Badge } from "@/components/ui/badge"
import type { DocumentListItemDto } from "@/lib/documents/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** What the user may do with a document, in words - shown with an icon, never by colour alone. */
const ACCESS: Record<
  DocumentListItemDto["myAccess"],
  { label: string; variant: "default" | "outline" | "muted" }
> = {
  manage: { label: "Full access", variant: "default" },
  edit: { label: "Can edit", variant: "outline" },
  view: { label: "View only", variant: "muted" },
  none: { label: "No access", variant: "muted" },
}

const iconButton =
  "flex size-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-primary focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"

/**
 * The newest documents the user can open (GET /api/v1/documents, newest
 * first, every folder): type, owner, when, version and their access - with
 * preview, download and open-its-folder on each row. A table on wide
 * screens, a list of cards on narrow ones.
 */
export function RecentDocuments({
  documents,
  owners,
  loading,
  failed,
  onRetry,
  onOpen,
  onDownload,
  order,
}: {
  documents?: DocumentListItemDto[]
  /** Owner names by user id (from the dashboard figures). */
  owners: Map<string, string>
  loading: boolean
  failed: boolean
  onRetry: () => void
  onOpen: (document: DocumentListItemDto) => void
  onDownload: (document: DocumentListItemDto) => void
  order: number
}) {
  return (
    <DashCard
      order={order}
      title="Recent documents"
      subtitle="The newest documents you can open"
      link={{ href: "/documents", label: "View all" }}
    >
      {loading ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          {[1, 2, 3, 4, 5].map((row) => (
            <div key={row} className="flex items-center gap-3">
              <DashSkeleton className="size-9 shrink-0 rounded-lg" />
              <div className="flex-1">
                <DashSkeleton className="mb-1.5 h-3.5 w-1/2" />
                <DashSkeleton className="h-3 w-1/3" />
              </div>
              <DashSkeleton className="hidden h-5 w-16 rounded-full md:block" />
            </div>
          ))}
        </div>
      ) : failed ? (
        <SectionError
          message="Your recent documents couldn't be loaded."
          onRetry={onRetry}
        />
      ) : !documents || documents.length === 0 ? (
        <EmptyState
          icon={FileText}
          title="No documents yet"
          text="Documents you upload, or that people give you access to, will show up here."
          action={{ href: "/documents", label: "Go to My Documents" }}
        />
      ) : (
        <>
          {/* Wide screens: a table. */}
          <div className="-mx-5 hidden overflow-x-auto md:block">
            <table className="w-full text-left text-[12.5px]">
              <thead>
                <tr className="border-b border-border text-[11px] tracking-wide text-muted-foreground uppercase">
                  <th
                    scope="col"
                    className="w-full py-2 pr-3 pl-5 font-semibold"
                  >
                    Name
                  </th>
                  <th scope="col" className="px-3 py-2 font-semibold">
                    Owner
                  </th>
                  <th scope="col" className="px-3 py-2 font-semibold">
                    Added
                  </th>
                  <th scope="col" className="px-3 py-2 font-semibold">
                    Version
                  </th>
                  <th scope="col" className="px-3 py-2 font-semibold">
                    Access
                  </th>
                  <th
                    scope="col"
                    className="py-2 pr-5 pl-3 text-right font-semibold"
                  >
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {documents.map((doc, i) => (
                  <tr
                    key={doc.documentId}
                    className="dash-row group border-b border-border transition-colors last:border-b-0 hover:bg-muted/50"
                    style={{ "--dash-order": i } as CSSProperties}
                  >
                    <td className="w-full max-w-0 py-2.5 pr-3 pl-5">
                      <NameCell doc={doc} onOpen={onOpen} />
                    </td>
                    <td className="px-3 py-2.5 whitespace-nowrap text-muted-foreground">
                      {owners.get(doc.ownerUserId) ?? "—"}
                    </td>
                    <td className="px-3 py-2.5 whitespace-nowrap text-muted-foreground">
                      <FriendlyDate iso={doc.createdAt} />
                    </td>
                    <td className="px-3 py-2.5">
                      {doc.versionNumber !== null && (
                        <span className="rounded-md bg-muted px-1.5 py-0.5 font-mono text-[11px] font-semibold">
                          v{doc.versionNumber}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2.5">
                      <AccessBadges doc={doc} />
                    </td>
                    <td className="py-2.5 pr-5 pl-3">
                      <RowActions
                        doc={doc}
                        onOpen={onOpen}
                        onDownload={onDownload}
                        className="justify-end opacity-60 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100"
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Narrow screens: a card per document. */}
          <ul className="flex flex-col gap-2 md:hidden">
            {documents.map((doc) => (
              <li
                key={doc.documentId}
                className="rounded-xl border border-border p-3"
              >
                <NameCell doc={doc} onOpen={onOpen} />
                <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1.5 pl-12 text-[11.5px] text-muted-foreground">
                  <span>{owners.get(doc.ownerUserId) ?? "—"}</span>
                  <FriendlyDate iso={doc.createdAt} />
                  {doc.versionNumber !== null && (
                    <span className="font-mono">v{doc.versionNumber}</span>
                  )}
                  <AccessBadges doc={doc} />
                </div>
                <RowActions
                  doc={doc}
                  onOpen={onOpen}
                  onDownload={onDownload}
                  className="mt-1.5 justify-end"
                />
              </li>
            ))}
          </ul>
        </>
      )}
    </DashCard>
  )
}

function NameCell({
  doc,
  onOpen,
}: {
  doc: DocumentListItemDto
  onOpen: (document: DocumentListItemDto) => void
}) {
  const kind = fileKind(doc.fileName)
  return (
    <button
      type="button"
      onClick={() => onOpen(doc)}
      className="flex w-full min-w-0 items-center gap-3 rounded-lg text-left focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <span
        className={cn(
          "flex size-9 shrink-0 items-center justify-center rounded-lg shadow-[inset_0_-2px_0_rgba(0,0,0,0.06)]",
          kind.tone
        )}
      >
        <kind.icon size={17} aria-hidden="true" />
        <span className="sr-only">{kind.label}</span>
      </span>
      <span className="min-w-0">
        <span className="block truncate text-[13px] font-medium group-hover:text-primary">
          {doc.title}
        </span>
        <span className="block truncate text-[11.5px] text-muted-foreground">
          {[
            doc.documentTypeName,
            kind.label,
            doc.sizeBytes !== null ? formatBytes(doc.sizeBytes) : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        </span>
      </span>
    </button>
  )
}

function AccessBadges({ doc }: { doc: DocumentListItemDto }) {
  const access = ACCESS[doc.myAccess] ?? ACCESS.view
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      <Badge variant={access.variant}>{access.label}</Badge>
      {doc.restricted && (
        <Badge
          variant="gold"
          title="Only the people and roles on its own list can open it"
        >
          <Lock size={10} aria-hidden="true" />
          Restricted
        </Badge>
      )}
    </span>
  )
}

function RowActions({
  doc,
  onOpen,
  onDownload,
  className,
}: {
  doc: DocumentListItemDto
  onOpen: (document: DocumentListItemDto) => void
  onDownload: (document: DocumentListItemDto) => void
  className?: string
}) {
  const folder = doc.folderId
    ? `/documents?folder=${encodeURIComponent(doc.folderId)}`
    : "/documents"
  return (
    <div className={cn("flex items-center gap-0.5", className)}>
      <Action
        icon={Eye}
        label={`Preview ${doc.title}`}
        tip="Preview"
        onClick={() => onOpen(doc)}
      />
      <Action
        icon={Download}
        label={`Download ${doc.title}`}
        tip="Download"
        onClick={() => onDownload(doc)}
      />
      <Link
        href={folder}
        aria-label={`Open the folder of ${doc.title}`}
        title="Open its folder"
        className={iconButton}
      >
        <FolderOpen size={15} aria-hidden="true" />
      </Link>
    </div>
  )
}

function Action({
  icon: Icon,
  label,
  tip,
  onClick,
}: {
  icon: LucideIcon
  label: string
  tip: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={tip}
      onClick={onClick}
      className={iconButton}
    >
      <Icon size={15} aria-hidden="true" />
    </button>
  )
}
