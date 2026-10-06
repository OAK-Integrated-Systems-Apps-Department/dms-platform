"use client"

import { useEffect, useState } from "react"
import { FilePlus, Loader2, Upload, X } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchIngestionTicket, uploadDocument } from "@/lib/documents/api"
import { useSelectableDocumentTypes } from "@/lib/documents/use-selectable-document-types"

import type { SupportingDocumentInput } from "@/lib/workflow/api"

import { DocumentPicker } from "./document-picker"

const TICKET_POLL_MS = 3000

/** A chosen document, with the title shown in the list. */
export interface PickedSupportingDocument extends SupportingDocumentInput {
  title: string
}

interface SupportingDocumentPickerProps {
  value: PickedSupportingDocument[]
  onChange: (docs: PickedSupportingDocument[]) => void
  /** The document under approval - never offered as its own evidence. */
  excludeDocumentId?: string
}

/**
 * Pick supporting documents (decisions 17-18, A10): search the documents you
 * can open and link one (its latest version is pinned), or upload a new one through
 * the normal upload - which needs docm.documents.create and a document type,
 * and may wait for its malware scan before it can be attached.
 */
export function SupportingDocumentPicker({ value, onChange, excludeDocumentId }: SupportingDocumentPickerProps) {
  const { hasPermission } = usePermissions()
  const canView = hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  const canUpload = hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE)
  const [mode, setMode] = useState<"link" | "upload">("link")

  if (!canView) {
    return (
      <p className="rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-[12.5px] text-[var(--gold-foreground)]">
        You can&apos;t attach documents (you don&apos;t have permission to view documents). Answer with a note instead.
      </p>
    )
  }

  function add(doc: PickedSupportingDocument) {
    if (value.some((d) => d.documentId === doc.documentId)) return
    onChange([...value, doc])
  }

  return (
    <div className="flex flex-col gap-3">
      {value.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {value.map((doc) => (
            <li key={doc.documentId} className="flex items-center justify-between gap-2 rounded-btn border border-border bg-muted px-3 py-1.5 text-[13px]">
              <span className="flex min-w-0 items-center gap-1.5">
                <FilePlus size={13} className="shrink-0 text-primary" />
                <span className="truncate">{doc.title}</span>
                <span className="text-[11.5px] text-muted-foreground">{doc.source === "uploaded_new" ? "(uploaded)" : "(linked)"}</span>
              </span>
              <button
                type="button"
                onClick={() => onChange(value.filter((d) => d.documentId !== doc.documentId))}
                className="flex size-6 items-center justify-center rounded-sm text-muted-foreground hover:text-foreground"
                aria-label={`Remove ${doc.title}`}
              >
                <X size={13} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {canUpload && (
        <SegmentedControl
          value={mode}
          onChange={setMode}
          options={[
            { value: "link" as const, label: "Link existing" },
            { value: "upload" as const, label: "Upload new" },
          ]}
        />
      )}
      {mode === "link" || !canUpload ? (
        <DocumentPicker
          excludeDocumentId={excludeDocumentId}
          pickedIds={value.map((d) => d.documentId)}
          onPick={(doc) => add({ documentId: doc.documentId, source: "linked_existing", title: doc.title })}
        />
      ) : (
        <UploadNew onAdd={add} />
      )}
    </div>
  )
}

function UploadNew({ onAdd }: { onAdd: (doc: PickedSupportingDocument) => void }) {
  const types = useSelectableDocumentTypes(true)
  const [file, setFile] = useState<File | null>(null)
  const [typeId, setTypeId] = useState("")
  const [title, setTitle] = useState("")
  const [state, setState] = useState<{ phase: "idle" | "uploading" | "scanning" | "error"; message?: string; ticket?: string }>({
    phase: "idle",
  })

  // A cloud scan answers 202 - poll the ticket until the document exists (or the scan fails).
  useEffect(() => {
    if (state.phase !== "scanning" || !state.ticket) return
    const id = window.setInterval(async () => {
      try {
        const ticket = await fetchIngestionTicket(state.ticket!)
        if (ticket.documentId) {
          window.clearInterval(id)
          onAdd({ documentId: ticket.documentId, source: "uploaded_new", title: title.trim() || ticket.originalFilename })
          setState({ phase: "idle" })
          setFile(null)
          setTitle("")
        } else if (ticket.status !== "pending") {
          window.clearInterval(id)
          setState({ phase: "error", message: ticket.message ?? "The file didn't pass the malware scan." })
        }
      } catch {
        // keep polling; a transient error isn't the scan's verdict
      }
    }, TICKET_POLL_MS)
    return () => window.clearInterval(id)
  }, [state.phase, state.ticket, onAdd, title])

  async function upload() {
    if (!file || !typeId) return
    setState({ phase: "uploading" })
    try {
      const ticket = await uploadDocument(file, typeId, title.trim() || file.name)
      if (ticket.documentId) {
        onAdd({ documentId: ticket.documentId, source: "uploaded_new", title: title.trim() || file.name })
        setState({ phase: "idle" })
        setFile(null)
        setTitle("")
      } else {
        setState({ phase: "scanning", ticket: ticket.scanResultId })
      }
    } catch (error) {
      setState({ phase: "error", message: error instanceof Error ? error.message : "Upload failed." })
    }
  }

  const busy = state.phase === "uploading" || state.phase === "scanning"
  return (
    <div className="flex flex-col gap-3">
      <div>
        <Label htmlFor="upload-file">File</Label>
        <Input id="upload-file" type="file" disabled={busy} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </div>
      <div className="flex flex-col gap-3 sm:flex-row">
        <div className="flex-1">
          <Label>Document type</Label>
          <SearchableSelect
            options={(types.data ?? []).map((t) => ({ value: t.documentTypeId, label: t.typeName }))}
            selected={typeId}
            onChange={setTypeId}
            allLabel={types.isPending ? "Loading types…" : "Choose a type"}
            searchPlaceholder="Search types"
          />
        </div>
        <div className="flex-1">
          <Label htmlFor="upload-title">Title (optional)</Label>
          <Input id="upload-title" value={title} disabled={busy} placeholder={file?.name ?? ""} onChange={(e) => setTitle(e.target.value)} />
        </div>
      </div>
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={!file || !typeId || busy}
          onClick={() => void upload()}
          className="flex items-center gap-1 rounded-btn border border-primary bg-primary/10 px-3 py-2 text-[12.5px] font-semibold text-primary disabled:opacity-50"
        >
          {busy ? <Loader2 size={13} className="animate-spin" /> : <Upload size={13} />} Upload and attach
        </button>
        {state.phase === "scanning" && (
          <span className="text-[12px] text-muted-foreground">Scanning the file for malware - it&apos;s attached once it passes.</span>
        )}
        {state.phase === "error" && <span className="text-[12px] text-destructive">{state.message}</span>}
      </div>
    </div>
  )
}
