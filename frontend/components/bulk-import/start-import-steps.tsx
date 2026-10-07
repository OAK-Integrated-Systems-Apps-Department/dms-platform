"use client"

import { useState } from "react"
import { AlertTriangle, Loader2, Play } from "lucide-react"

import { Button } from "@/components/ui/button"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { friendlyMessage } from "@/lib/api-error"
import type { ImportDefaultsDto, ImportDocumentTypeOptionDto } from "@/lib/bulk-imports/api"
import { useImportDefaults } from "@/lib/bulk-imports/use-import-defaults"

import { InlineError, StepCard } from "./step-card"

interface StartImportStepsProps {
  /** Active document types only, in display order (filtered server-side). */
  documentTypes: ImportDocumentTypeOptionDto[]
  documentTypesLoading: boolean
  documentTypesError: string | null
  creating: boolean
  /** Opens a batch for the type; resolves with an error message, or null on success. */
  onCreate: (documentTypeId: string) => Promise<string | null>
}

/**
 * Step 1: choose the document type every file will become, see the default
 * values that will be applied (and any that aren't valid, or required fields
 * that will be left empty), then start the import. A type whose required fields must have
 * defaults (`enforceMandatoryFields`) and lack some can't be started.
 */
export function StartImportSteps({
  documentTypes,
  documentTypesLoading,
  documentTypesError,
  creating,
  onCreate,
}: StartImportStepsProps) {
  const [documentTypeId, setDocumentTypeId] = useState("")
  const [createError, setCreateError] = useState<string | null>(null)
  const defaultsQuery = useImportDefaults(documentTypeId || null)
  const defaults = defaultsQuery.data

  const blocked = Boolean(defaults?.enforceMandatoryFields && defaults.mandatoryFieldsWithoutDefault.length > 0)
  const canStart = Boolean(documentTypeId) && Boolean(defaults) && !blocked && !creating

  async function handleStart() {
    if (!canStart) return
    setCreateError(null)
    const error = await onCreate(documentTypeId)
    if (error) setCreateError(error)
  }

  return (
    <StepCard
      step={1}
      title="Choose a document type"
      description="Every file you add becomes a document of this type, with the type's default values filled in."
    >
      {documentTypesLoading ? (
        <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" />
          Loading document types…
        </p>
      ) : documentTypesError ? (
        <InlineError>{documentTypesError}</InlineError>
      ) : documentTypes.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">There are no active document types to import into.</p>
      ) : (
        <SearchableSelect
          options={documentTypes.map((type) => ({ value: type.documentTypeId, label: type.name }))}
          selected={documentTypeId}
          onChange={(value) => {
            setDocumentTypeId(value)
            setCreateError(null)
          }}
          allLabel="Select a document type"
          searchPlaceholder="Search document types…"
          className="w-[300px] max-w-full"
        />
      )}

      {documentTypeId && (
        <section className="mt-5" aria-live="polite">
          {defaultsQuery.isLoading ? (
            <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              Loading default values…
            </p>
          ) : defaultsQuery.error ? (
            <InlineError>
              {friendlyMessage(defaultsQuery.error, "Could not load the default values for this document type.")}
            </InlineError>
          ) : defaults ? (
            <DefaultsSummary defaults={defaults} blocked={blocked} />
          ) : null}

          <div className="mt-4">
            <Button onClick={handleStart} disabled={!canStart}>
              {creating ? <Loader2 className="animate-spin" /> : <Play />}
              {creating ? "Starting import…" : "Start import"}
            </Button>
          </div>
          {createError && <InlineError>{createError}</InlineError>}
        </section>
      )}
    </StepCard>
  )
}

function DefaultsSummary({ defaults, blocked }: { defaults: ImportDefaultsDto; blocked: boolean }) {
  const missing = defaults.mandatoryFieldsWithoutDefault.map((field) => field.label).join(", ")

  return (
    <>
      {defaults.defaults.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">
          {defaults.invalidDefaults.length > 0
            ? "No valid default values are set for this type."
            : "No default values are set for this type."}
        </p>
      ) : (
        <>
          <h3 className="mb-2 text-[13px] font-semibold">These values will be applied to every document:</h3>
          <dl className="grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-6 gap-y-1.5 rounded-md border border-border px-3.5 py-2.5 text-[13px]">
            {defaults.defaults.map((value) => (
              <div key={value.fieldKey} className="contents">
                <dt className="text-muted-foreground">{value.label}</dt>
                <dd className="font-medium break-words">{value.value}</dd>
              </div>
            ))}
          </dl>
        </>
      )}

      {defaults.invalidDefaults.length > 0 && (
        <div className="mt-3 flex items-start gap-2 rounded-btn border border-border px-3.5 py-2.5 text-[12.5px] leading-[1.55]">
          <AlertTriangle size={15} className="mt-px shrink-0 text-[var(--rust)]" aria-hidden="true" />
          <div>
            <p className="font-medium">These default values aren&apos;t valid and won&apos;t be applied:</p>
            <ul className="mt-1 space-y-0.5">
              {defaults.invalidDefaults.map((invalid) => (
                <li key={invalid.fieldKey} className="break-words">
                  {invalid.label} (&apos;{invalid.value}&apos;) - {invalid.reason}.
                </li>
              ))}
            </ul>
            <p className="mt-1.5 text-muted-foreground">
              An administrator can fix them in the document type&apos;s Fields tab.
            </p>
          </div>
        </div>
      )}

      {missing &&
        (blocked ? (
          <InlineError>
            This type can&apos;t be imported until an administrator sets default values for its required fields:{" "}
            {missing}.
          </InlineError>
        ) : (
          <p className="mt-3 text-[12.5px] text-muted-foreground">
            These required fields will be left empty for now: {missing}.
          </p>
        ))}
    </>
  )
}
