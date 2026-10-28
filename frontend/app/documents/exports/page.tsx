import { redirect } from "next/navigation"

/**
 * Exports were replaced by Google Drive style downloads in My Documents
 * (a folder, or several documents, as one ZIP); old links still work.
 */
export default function LegacyExportsPage() {
  redirect("/documents")
}
