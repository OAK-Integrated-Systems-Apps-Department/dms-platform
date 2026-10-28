import { redirect } from "next/navigation"

/**
 * Bulk Import (and its import templates) was removed: documents are uploaded
 * in My Documents, starting from a document type's templates where it has
 * them. Old links and bookmarks still work.
 */
export default function LegacyBulkImportPage() {
  redirect("/documents")
}
