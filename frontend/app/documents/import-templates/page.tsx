"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Download, Pencil, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { PermissionGate } from "@/components/admin/permission-gate"
import { ConfirmActionDialog } from "@/components/bulk-import/confirm-action-dialog"
import { ImportTemplateEditor } from "@/components/import-templates/import-template-editor"
import { DocumentsHub } from "@/components/documents/documents-hub"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { saveDownloadedFile } from "@/lib/bulk-imports/api"
import { useImportDocumentTypes } from "@/lib/bulk-imports/use-import-document-types"
import {
  deleteImportTemplate,
  downloadSavedImportTemplate,
  fetchImportTemplates,
  type ImportTemplateDto,
} from "@/lib/import-templates/api"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"

const TH =
  "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
const MANAGE = [
  PermissionCodes.FIE_IMPORT_TEMPLATES_CREATE,
  PermissionCodes.FIE_IMPORT_TEMPLATES_UPDATE,
  PermissionCodes.FIE_IMPORT_TEMPLATES_DELETE,
]

/**
 * Import Templates (FIE "Manage Import Templates"): the tenant's saved bulk
 * import templates. Each fixes a document type's columns, their order and
 * headers, and defaults for the rest; everyone who can bulk import picks
 * them from the Bulk Import page. Create/edit/delete each need their own
 * fie.manage-import-templates.* code (checked server-side too).
 */
export default function ImportTemplatesPage() {
  usePageTitle("Import Templates")

  return (
    <DashboardShell initialActiveLabel="My Documents">
      <DocumentsHub active="import-templates">
        <PermissionGate permission={MANAGE}>
          <div className="max-w-[1040px]">
            <TemplatesList />
          </div>
        </PermissionGate>
      </DocumentsHub>
    </DashboardShell>
  )
}

function TemplatesList() {
  const queryClient = useQueryClient()
  const { hasPermission } = usePermissions()
  const canCreate = hasPermission(PermissionCodes.FIE_IMPORT_TEMPLATES_CREATE)
  const canUpdate = hasPermission(PermissionCodes.FIE_IMPORT_TEMPLATES_UPDATE)
  const canDelete = hasPermission(PermissionCodes.FIE_IMPORT_TEMPLATES_DELETE)

  const templates = useQuery({
    queryKey: ["import-templates", "all"],
    queryFn: () => fetchImportTemplates(),
  })
  const { documentTypes } = useImportDocumentTypes()
  const [editing, setEditing] = useState<{
    template: ImportTemplateDto | null
    key: number
  } | null>(null)
  const [deleting, setDeleting] = useState<ImportTemplateDto | null>(null)

  async function download(template: ImportTemplateDto) {
    try {
      saveDownloadedFile(
        await downloadSavedImportTemplate(template.importTemplateId, "xlsx")
      )
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the template."))
    }
  }

  async function confirmDelete(): Promise<string | null> {
    if (!deleting) return null
    try {
      await deleteImportTemplate(deleting.importTemplateId)
      toast.success(`Deleted "${deleting.name}".`)
      setDeleting(null)
      await queryClient.invalidateQueries({ queryKey: ["import-templates"] })
      return null
    } catch (error) {
      return friendlyMessage(error, "Could not delete the template.")
    }
  }

  const rows = templates.data ?? []

  return (
    <>
      <div className="mb-3 flex justify-end">
        {canCreate && (
          <Button
            onClick={() => setEditing({ template: null, key: Date.now() })}
          >
            <Plus />
            New template
          </Button>
        )}
      </div>

      {templates.isPending ? (
        <LoadingState message="Loading templates…" />
      ) : templates.isError ? (
        <p className="text-[13px] text-[var(--rust)]">
          {friendlyMessage(templates.error, "Could not load the templates.")}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
          <table className="w-full min-w-[760px] border-collapse">
            <thead>
              <tr className="border-b border-border">
                <th className={TH}>Name</th>
                <th className={TH}>Document type</th>
                <th className={TH}>Columns</th>
                <th className={TH}>Defaults</th>
                <th className={TH}>Last changed</th>
                <th className={TH}>
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td
                    colSpan={6}
                    className="px-3 py-10 text-center text-[13px] text-muted-foreground"
                  >
                    No saved templates yet. Bulk Import offers each document
                    type&apos;s standard template until you add one.
                  </td>
                </tr>
              )}
              {rows.map((t) => (
                <tr
                  key={t.importTemplateId}
                  className="border-b border-border align-top last:border-b-0"
                >
                  <td className="px-3 py-3">
                    <p className="text-[13.5px] font-semibold">{t.name}</p>
                    {t.description && (
                      <p className="text-[12px] text-muted-foreground">
                        {t.description}
                      </p>
                    )}
                    {t.missingFields.length > 0 && (
                      <p className="mt-1 text-[12px] text-[var(--gold-foreground)]">
                        Uses fields no longer on the form:{" "}
                        {t.missingFields.join(", ")} - edit to tidy up.
                      </p>
                    )}
                  </td>
                  <td className="px-3 py-3 text-[13px]">
                    {t.documentTypeName ?? "—"}
                  </td>
                  <td className="px-3 py-3 text-[13px]">
                    {t.columns.length + 2}
                  </td>
                  <td className="px-3 py-3 text-[12.5px] text-muted-foreground">
                    {Object.keys(t.defaults).length === 0
                      ? "—"
                      : Object.entries(t.defaults)
                          .map(([key, value]) => `${key} = ${value}`)
                          .join(", ")}
                  </td>
                  <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                    {formatDateTime(t.updatedAt ?? t.createdAt)}
                  </td>
                  <td className="px-3 py-3">
                    <div className="flex justify-end gap-1.5">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => void download(t)}
                      >
                        <Download />
                        Download
                      </Button>
                      {canUpdate && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            setEditing({ template: t, key: Date.now() })
                          }
                        >
                          <Pencil />
                          Edit
                        </Button>
                      )}
                      {canDelete && (
                        <Button
                          variant="outline"
                          size="sm"
                          aria-label={`Delete ${t.name}`}
                          onClick={() => setDeleting(t)}
                        >
                          <Trash2 />
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && (
        <ImportTemplateEditor
          key={editing.key}
          open
          onOpenChange={(open) => !open && setEditing(null)}
          template={editing.template}
          documentTypes={documentTypes}
        />
      )}
      <ConfirmActionDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`Delete "${deleting?.name ?? ""}"?`}
        description="People can no longer download it, and files already made from it can't be uploaded - they'll be asked to download a fresh template."
        confirmLabel="Delete template"
        pendingLabel="Deleting…"
        onConfirm={confirmDelete}
      />
    </>
  )
}
