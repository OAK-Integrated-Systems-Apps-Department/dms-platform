"use client"

import { useState } from "react"
import Link from "next/link"
import type { LucideIcon } from "lucide-react"
import { ChevronDown, Download, ExternalLink, Pin, PinOff } from "lucide-react"

import {
  DashCard,
  DashSkeleton,
  SectionError,
} from "@/components/dashboard/dash-card"
import { fileKind } from "@/components/documents/browser-items"
import { Badge } from "@/components/ui/badge"
import type { DocumentListItemDto } from "@/lib/documents/api"
import type { PinnedDocumentDto } from "@/lib/pins/api"
import { usePins } from "@/lib/pins/use-pins"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** Tiles shown before "Show all" - two rows at the widest layout. */
const VISIBLE_TILES = 8

const iconButton =
  "flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-primary focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"

/**
 * The documents this user pinned, as a shelf of tiles at the top of the dashboard (PID-FR-002):
 * one click opens the preview, and the quick actions - preview, download, the document's page,
 * unpin - are on each tile (PID-FR-005). It stays small: a few rows of tiles, the rest behind
 * "Show all", and when nothing is pinned a single line rather than an empty card.
 *
 * Personal to the signed-in user: the server only ever returns their own pins, and only those
 * they can still open (PID-FR-006/008/009).
 */
export function PinnedDocuments({
  onOpen,
  onDownload,
  order,
}: {
  onOpen: (document: DocumentListItemDto) => void
  onDownload: (document: DocumentListItemDto) => void
  order: number
}) {
  const { pins, limit, count, isLoading, isError, refetch, canUnpin, toggle } =
    usePins()
  const [showAll, setShowAll] = useState(false)

  // Nothing pinned: one quiet line, not a card of its own.
  if (!isLoading && !isError && pins.length === 0) {
    return (
      <p className="mb-5 flex items-center gap-2 px-1 text-[12.5px] text-muted-foreground">
        <Pin size={13} aria-hidden="true" className="shrink-0" />
        <span>
          Pin the documents you open most and they&apos;ll sit here, one click
          away. Use the pin on any document in{" "}
          <Link href="/documents" className="font-medium text-primary hover:underline">
            My Documents
          </Link>
          .
        </span>
      </p>
    )
  }

  const shown = showAll ? pins : pins.slice(0, VISIBLE_TILES)
  const hidden = pins.length - shown.length

  return (
    <DashCard
      order={order}
      className="mb-5"
      title="Pinned documents"
      subtitle={
        limit !== null ? `${count} of ${limit} · your own shortcuts` : undefined
      }
    >
      {isLoading ? (
        <div
          aria-busy="true"
          className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
        >
          {[1, 2, 3, 4].map((tile) => (
            <DashSkeleton key={tile} className="h-[68px] rounded-xl" />
          ))}
        </div>
      ) : isError ? (
        <SectionError
          message="Your pinned documents couldn't be loaded."
          onRetry={() => void refetch()}
        />
      ) : (
        <>
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {shown.map((pin) => (
              <PinnedTile
                key={pin.document.documentId}
                pin={pin}
                canUnpin={canUnpin}
                onOpen={onOpen}
                onDownload={onDownload}
                onUnpin={() => void toggle(pin.document)}
              />
            ))}
          </ul>
          {(hidden > 0 || showAll) && pins.length > VISIBLE_TILES && (
            <button
              type="button"
              onClick={() => setShowAll((open) => !open)}
              aria-expanded={showAll}
              className="mt-3 flex items-center gap-1 text-[12.5px] font-semibold text-primary hover:underline focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              {showAll ? "Show fewer" : `Show all ${pins.length}`}
              <ChevronDown
                size={14}
                aria-hidden="true"
                className={cn("transition-transform", showAll && "rotate-180")}
              />
            </button>
          )}
        </>
      )}
    </DashCard>
  )
}

function PinnedTile({
  pin,
  canUnpin,
  onOpen,
  onDownload,
  onUnpin,
}: {
  pin: PinnedDocumentDto
  canUnpin: boolean
  onOpen: (document: DocumentListItemDto) => void
  onDownload: (document: DocumentListItemDto) => void
  onUnpin: () => void
}) {
  const doc = pin.document
  const kind = fileKind(doc.fileName)
  return (
    <li className="group relative flex min-w-0 items-center rounded-xl border border-border bg-background transition-colors focus-within:border-primary/50 hover:border-primary/50">
      <button
        type="button"
        onClick={() => onOpen(doc)}
        aria-label={`Preview ${doc.title}`}
        className="flex min-w-0 flex-1 items-center gap-3 rounded-xl p-3 pr-3 text-left focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <span
          className={cn(
            "flex size-10 shrink-0 items-center justify-center rounded-lg shadow-[inset_0_-2px_0_rgba(0,0,0,0.06)]",
            kind.tone
          )}
        >
          <kind.icon size={18} aria-hidden="true" />
        </span>
        <span className="min-w-0">
          <span className="block truncate text-[13px] font-medium group-hover:text-primary">
            {doc.title}
          </span>
          <span className="flex items-center gap-1.5 text-[11.5px] text-muted-foreground">
            <span className="truncate">
              {[
                doc.documentTypeName ?? kind.label,
                doc.sizeBytes !== null ? formatBytes(doc.sizeBytes) : null,
              ]
                .filter(Boolean)
                .join(" · ")}
            </span>
            {pin.archived && (
              <Badge
                variant="muted"
                title="This document has been archived - it is still here, but no longer current"
              >
                Archived
              </Badge>
            )}
          </span>
        </span>
      </button>
      {/* Quick actions sit over the tile's right edge - on hover or focus, always on touch screens. */}
      <div
        className={cn(
          "absolute top-1.5 right-1.5 flex items-center gap-0.5 rounded-lg border border-border bg-card/95 p-0.5 shadow-sm",
          "opacity-0 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100 [@media(hover:none)]:opacity-100"
        )}
      >
        <Action
          icon={Download}
          label={`Download ${doc.title}`}
          tip="Download"
          onClick={() => onDownload(doc)}
        />
        <Link
          href={`/documents/${doc.documentId}`}
          aria-label={`Open the page of ${doc.title}`}
          title="Details"
          className={iconButton}
        >
          <ExternalLink size={14} aria-hidden="true" />
        </Link>
        {canUnpin && (
          <Action
            icon={PinOff}
            label={`Unpin ${doc.title}`}
            tip="Unpin"
            onClick={onUnpin}
          />
        )}
      </div>
    </li>
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
      <Icon size={14} aria-hidden="true" />
    </button>
  )
}

