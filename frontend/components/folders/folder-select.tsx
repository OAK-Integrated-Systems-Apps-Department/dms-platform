"use client"

import { SearchableSelect } from "@/components/ui/searchable-select"
import { hasAccess, type FolderAccessLevel } from "@/lib/folders/api"
import { useFolders } from "@/lib/folders/use-folders"

interface FolderSelectProps {
  /** "" = not in a folder. */
  value: string
  onChange: (folderId: string) => void
  /** Folders that can't be picked (e.g. a folder and its subfolders when moving it). */
  exclude?: Set<string>
  /** What "" is called - "No folder (top level)" for documents, "Top level" for folders. */
  noneLabel?: string
  className?: string
  disabled?: boolean
  /** Only folders the caller has at least this access to (V53) - edit for filing things, view for exports. */
  needs?: FolderAccessLevel
}

/** Pick a folder by its full path ("Finance / Invoices / 2026"). */
export function FolderSelect({
  value,
  onChange,
  exclude,
  noneLabel = "No folder (top level)",
  className,
  disabled,
  needs = "edit",
}: FolderSelectProps) {
  const { flat, isPending } = useFolders()
  const options = flat
    .filter(
      (folder) =>
        !exclude?.has(folder.folderId) && hasAccess(folder.myAccess, needs)
    )
    .map((folder) => ({ value: folder.folderId, label: folder.path }))
  return (
    <div className={disabled ? "pointer-events-none opacity-60" : undefined}>
      <SearchableSelect
        options={options}
        selected={value}
        onChange={onChange}
        allLabel={isPending ? "Loading folders…" : noneLabel}
        searchPlaceholder="Search folders…"
        className={className ?? "w-[300px] max-w-full"}
      />
    </div>
  )
}
