"use client"

import Link from "next/link"
import { Link2 } from "lucide-react"

import { buttonVariants } from "@/components/ui/button"
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
      // The same look and size as the other buttons beside it (View details, Download).
      className={buttonVariants({ variant: "outline", size: "sm" })}
    >
      <Link2 />
      Relationships ({count})
    </Link>
  )
}
