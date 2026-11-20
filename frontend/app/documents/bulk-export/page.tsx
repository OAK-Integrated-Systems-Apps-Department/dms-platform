import { redirect } from "next/navigation"

/** Bulk Export was replaced by downloads in Documents (a folder, or several documents, as one ZIP); old links still work. */
export default function LegacyBulkExportPage() {
  redirect("/documents")
}
