import { File, FileSpreadsheet, FileText, Folder, type LucideIcon } from "lucide-react"

import type { DocumentFileType } from "./types"

/** Maps a document's file type to the icon used to represent it. */
export function fileIcon(type: DocumentFileType): LucideIcon {
  if (type === "xlsx") return FileSpreadsheet
  if (type === "docx") return File
  if (type === "folder") return Folder
  return FileText
}