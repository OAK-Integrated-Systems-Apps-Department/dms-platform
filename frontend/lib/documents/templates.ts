import { API_BASE_URL } from "@/lib/api-config"
import {
  apiFetch,
  apiFetchJson,
  apiUpload,
  type UploadTransfer,
} from "@/lib/api-fetch"
import type { IngestionTicketDto } from "@/lib/documents/api"

const BASE = `${API_BASE_URL}/api/v1/document-templates`

/**
 * Mirrors DocumentTemplateService.TemplateItem: a ready-made file a document
 * type offers (a blank contract, a proposal, a form) to download or start a
 * new document from. `templateId` is also its document id, so download and
 * preview use the ordinary document endpoints.
 */
export interface DocumentTemplateDto {
  templateId: string
  documentTypeId: string
  documentTypeName: string
  /** Its document type is active - only then can a new document start from it. */
  active: boolean
  name: string
  fileName: string | null
  mimeType: string | null
  sizeBytes: number | null
  versionLabel: string | null
  updatedAt: string
}

export const TEMPLATES_KEY = ["document-templates"] as const

/** Every document type's templates, or one type's - by name. */
export async function fetchTemplates(
  documentTypeId?: string
): Promise<DocumentTemplateDto[]> {
  const query = documentTypeId
    ? `?documentTypeId=${encodeURIComponent(documentTypeId)}`
    : ""
  return apiFetchJson(`${BASE}${query}`, undefined, "Could not load templates.")
}

/** Adds a template to a document type - virus-scanned before it's offered. */
export async function addTemplate(
  documentTypeId: string,
  file: File,
  name: string,
  transfer?: UploadTransfer
): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  const params = new URLSearchParams({ documentTypeId, name })
  return apiUpload(
    `${BASE}?${params.toString()}`,
    form,
    transfer,
    "Could not add the template."
  )
}

/** A new file for the template; the old one stays in its version history. */
export async function replaceTemplate(
  templateId: string,
  file: File,
  transfer?: UploadTransfer
): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  return apiUpload(
    `${BASE}/${templateId}/file`,
    form,
    transfer,
    "Could not replace the template."
  )
}

export async function renameTemplate(
  templateId: string,
  name: string
): Promise<DocumentTemplateDto> {
  return apiFetchJson(
    `${BASE}/${templateId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    },
    "Could not rename the template."
  )
}

export async function removeTemplate(templateId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${templateId}`,
    { method: "DELETE" },
    "Could not remove the template."
  )
}

/** A new document in `folderId` (null = the top level) starting from the template. */
export async function copyTemplate(
  templateId: string,
  folderId: string | null,
  title: string
): Promise<IngestionTicketDto> {
  return apiFetchJson(
    `${BASE}/${templateId}/copy`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ folderId, title }),
    },
    "Could not create the document."
  )
}
