"use client"

import Link from "next/link"
import { Eye, Gavel } from "lucide-react"

import { BookmarkButton } from "@/components/bookmarks/bookmark-button"
import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { DocumentThumbnail } from "@/components/documents/document-thumbnail"
import { PinButton } from "@/components/pins/pin-button"
import { LifecycleBadge } from "@/components/retention/lifecycle-badge"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type { SearchFilterOptionsDto, SearchItemDto } from "@/lib/search/types"
import { cn } from "@/lib/utils"

import { Snippet } from "./snippet"

/**
 * One result: what it is (name, type, key metadata), why it matched (highlighted title, file name and text snippets),
 * and a click through to the document page. The text snippets come only for documents the server verified you may open.
 */
export function SearchResultCard({
  item,
  options,
  onPreview,
  bookmarked,
}: {
  item: SearchItemDto
  options: SearchFilterOptionsDto | undefined
  /** Opens the preview panel for this result (the page keeps it in the URL). */
  onPreview?: (documentId: string) => void
  /** The bookmark mark to show; defaults to what the server said for this page. */
  bookmarked?: boolean
}) {
  const kind = fileKind(item.fileName)
  const owner = options?.owners.find((o) => o.id === item.ownerUserId)?.name
  const department = options?.departments.find((d) => d.id === item.ownerDepartmentId)?.name
  const { title, fileName, body } = item.highlights

  return (
    <li className="rounded-lg border border-border bg-card p-4 shadow-[var(--ledger-shadow)]">
      <div className="flex items-start gap-3">
        {item.thumbnailUrl ? (
          // The server only gives a link to a caller who may use it; the picture falls back to the file-type icon.
          <span
            className={cn("relative size-14 shrink-0 overflow-hidden rounded-lg", kind.tone)}
            data-testid="result-thumbnail"
          >
            <DocumentThumbnail
              documentId={item.documentId}
              fileName={item.fileName}
              versionNumber={item.versionNumber ?? null}
            >
              <kind.icon size={20} />
            </DocumentThumbnail>
          </span>
        ) : (
          <span className={cn("flex size-10 shrink-0 items-center justify-center rounded-lg", kind.tone)}>
            <kind.icon size={18} />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <Link
              href={`/documents/${item.documentId}`}
              className="text-[15px] font-semibold text-primary hover:underline"
            >
              {title ? <Snippet html={title} /> : item.title}
            </Link>
            <LifecycleBadge state={item.lifecycleState} hideActive />
            {item.legalHold && (
              <Badge variant="gold">
                <Gavel /> Legal hold
              </Badge>
            )}
            <span className="ml-auto flex items-center gap-1">
              {onPreview && (
                <Button
                  type="button"
                  variant="outline"
                  size="xs"
                  aria-label={`Preview ${item.title}`}
                  onClick={() => onPreview(item.documentId)}
                >
                  <Eye size={14} /> Preview
                </Button>
              )}
              <PinButton row={{ documentId: item.documentId, title: item.title }} pinned={item.pinned} />
              <BookmarkButton
                row={{ documentId: item.documentId, title: item.title }}
                bookmarked={bookmarked ?? item.bookmarked ?? false}
              />
            </span>
          </div>
          <p className="mt-0.5 truncate text-[12px] text-muted-foreground">
            {fileName ? <Snippet html={fileName} /> : item.fileName}
            {[item.documentTypeName, owner, department].filter(Boolean).map((part) => (
              <span key={part}> · {part}</span>
            ))}
            <span>
              {" · "}
              {item.updatedAt ? (
                <>
                  Modified <FriendlyDate iso={item.updatedAt} />
                </>
              ) : (
                <>
                  Created <FriendlyDate iso={item.createdAt} />
                </>
              )}
            </span>
          </p>
          {body.length > 0 && (
            <div className="mt-2 grid gap-1">
              {body.map((snippet, i) => (
                <p key={i} className="text-[13px] leading-relaxed text-foreground/80">
                  … <Snippet html={snippet} /> …
                </p>
              ))}
            </div>
          )}
          {item.bodyStatus === "pending" && (
            <p className="mt-2 text-[12px] text-muted-foreground">
              The words inside this file are still being read, so it can only be found by its name and details for now.
            </p>
          )}
          {item.bodyStatus === "unavailable" && (
            <p className="mt-2 text-[12px] text-muted-foreground">
              The words inside this file couldn&rsquo;t be read, so it is searched by its name and details only.
            </p>
          )}
        </div>
      </div>
    </li>
  )
}
