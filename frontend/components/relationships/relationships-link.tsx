"use client"

import Link from "next/link"
import { Link2 } from "lucide-react"

import { useRelationshipCounts } from "@/lib/relationships/use-document-relationships"

/**
 * "Relationships (n)" - one click from a document to its Related Documents panel. Shown only
 * once the server has counted the links, which it does only for documents the viewer can open.
 */
export function RelationshipsLink({ documentId }: { documentId: string }) {
  const counts = useRelationshipCounts([documentId])
  const count = counts.data?.counts[documentId]
  if (count === undefined) return null
  return (
    <Link
      href={`/documents/${documentId}#relationships`}
      className="inline-flex h-7 items-center gap-1 rounded-[min(var(--radius-md),12px)] border border-border bg-background px-2.5 text-[0.8rem] font-medium whitespace-nowrap hover:bg-muted"
    >
      <Link2 className="size-3.5" />
      Relationships ({count})
    </Link>
  )
}
