"use client"

import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { FileText, Plus } from "lucide-react"

import { SearchInput } from "@/components/ui/search-input"
import { fetchDocuments, type DocumentListItemDto } from "@/lib/documents/api"
import { formatDateTime } from "@/lib/utils"

const RESULTS = 8

interface DocumentPickerProps {
  /** Left out of the results - the document under approval. */
  excludeDocumentId?: string
  /** Already chosen - hidden from the results. */
  pickedIds: string[]
  onPick: (document: DocumentListItemDto) => void
}

/**
 * Search the tenant's documents by title and pick one (the person-picker
 * pattern: debounced server-side search, a results list under the box).
 * With an empty search it lists the newest documents.
 *
 * Backed by "Browse documents" (`GET /api/v1/documents`, all=true), so the
 * results are exactly the documents this person may open.
 */
export function DocumentPicker({ excludeDocumentId, pickedIds, onPick }: DocumentPickerProps) {
  const [search, setSearch] = useState("")
  const [debounced, setDebounced] = useState("")

  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(search), 250)
    return () => window.clearTimeout(id)
  }, [search])

  const documents = useQuery({
    queryKey: ["documents", "picker", debounced, excludeDocumentId],
    queryFn: () =>
      fetchDocuments({
        folderId: null,
        all: true,
        q: debounced,
        excludeDocumentId,
        page: 0,
        size: RESULTS + pickedIds.length,
      }),
    staleTime: 30_000,
    retry: false,
  })
  const rows = (documents.data?.content ?? []).filter((d) => !pickedIds.includes(d.documentId)).slice(0, RESULTS)

  return (
    <div className="flex flex-col gap-2">
      <SearchInput value={search} onChange={setSearch} placeholder="Search documents by title" />
      <ul className="max-h-[240px] overflow-y-auto rounded-btn border border-border" role="listbox" aria-label="Documents">
        {documents.isPending && <li className="px-3 py-2 text-[12.5px] text-muted-foreground">Searching…</li>}
        {documents.isError && (
          <li className="px-3 py-2 text-[12.5px] text-[var(--rust)]">
            {documents.error instanceof Error ? documents.error.message : "Could not load documents."}
          </li>
        )}
        {!documents.isPending && !documents.isError && rows.length === 0 && (
          <li className="px-3 py-2 text-[12.5px] text-muted-foreground">No documents found</li>
        )}
        {rows.map((doc) => (
          <li key={doc.documentId}>
            <button
              type="button"
              role="option"
              aria-selected={false}
              onClick={() => onPick(doc)}
              className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-[13px] hover:bg-muted"
            >
              <span className="flex min-w-0 items-start gap-2">
                <FileText size={14} className="mt-0.5 shrink-0 text-muted-foreground" />
                <span className="min-w-0">
                  <span className="block truncate font-medium text-foreground">{doc.title}</span>
                  <span className="block text-[11.5px] text-muted-foreground">
                    {[doc.documentTypeName ?? "No type", doc.fileName, formatDateTime(doc.createdAt)]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
              </span>
              <Plus size={14} className="shrink-0 text-primary" aria-hidden="true" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
