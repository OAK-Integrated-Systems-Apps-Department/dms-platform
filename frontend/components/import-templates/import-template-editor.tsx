"use client"

import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { ArrowDown, ArrowUp, Loader2, Plus, X } from "lucide-react"
import { toast } from "sonner"

import { InlineError } from "@/components/bulk-import/step-card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Textarea } from "@/components/ui/textarea"
import { friendlyMessage } from "@/lib/api-error"
import type { ImportDocumentTypeOptionDto } from "@/lib/bulk-imports/api"
import {
  createImportTemplate,
  fetchImportTemplateFields,
  updateImportTemplate,
  type ImportTemplateDto,
  type ImportTemplateFieldDto,
  type ImportTemplateRequestDto,
} from "@/lib/import-templates/api"

const LABEL =
  "mb-1.5 block text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

interface ImportTemplateEditorProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The template being edited; null to create one. Read once - remount (new `key`) per opening. */
  template: ImportTemplateDto | null
  documentTypes: ImportDocumentTypeOptionDto[]
}

/**
 * Create or edit a saved import template: which fields are columns (and in
 * what order, under which header), and default values for fields left out
 * or left blank. The server checks every default like an imported cell and
 * refuses a template that leaves a required field with neither a column
 * nor a default; the same rule is shown here as you edit.
 */
export function ImportTemplateEditor({
  open,
  onOpenChange,
  template,
  documentTypes,
}: ImportTemplateEditorProps) {
  const queryClient = useQueryClient()
  const [documentTypeId, setDocumentTypeId] = useState(
    template?.documentTypeId ?? ""
  )
  const [name, setName] = useState(template?.name ?? "")
  const [description, setDescription] = useState(template?.description ?? "")
  // null = untouched new template: every field is a column, like the standard template.
  const [columns, setColumns] = useState<string[] | null>(
    template ? template.columns.map((c) => c.fieldKey) : null
  )
  const [headers, setHeaders] = useState<Record<string, string>>(
    Object.fromEntries(
      (template?.columns ?? [])
        .filter((c) => c.header)
        .map((c) => [c.fieldKey, c.header ?? ""])
    )
  )
  const [defaults, setDefaults] = useState<Record<string, string>>(
    template?.defaults ?? {}
  )

  const fieldsQuery = useQuery({
    queryKey: ["import-template-fields", documentTypeId],
    queryFn: () => fetchImportTemplateFields(documentTypeId),
    enabled: Boolean(documentTypeId),
  })
  const fields = fieldsQuery.data ?? []
  const byKey = new Map(fields.map((f) => [f.fieldKey, f]))
  const included = (columns ?? fields.map((f) => f.fieldKey)).filter((key) =>
    byKey.has(key)
  )
  const excluded = fields.filter((f) => !included.includes(f.fieldKey))
  const uncovered = fields.filter(
    (f) =>
      f.mandatory &&
      !included.includes(f.fieldKey) &&
      !defaults[f.fieldKey]?.trim()
  )

  const save = useMutation({
    mutationFn: () => {
      const request: ImportTemplateRequestDto = {
        name: name.trim(),
        description: description.trim() || null,
        documentTypeId,
        columns: included.map((key) => ({
          fieldKey: key,
          header: headers[key]?.trim() || null,
        })),
        defaults: Object.fromEntries(
          Object.entries(defaults).filter(
            ([key, value]) => value.trim() && byKey.has(key)
          )
        ),
      }
      return template
        ? updateImportTemplate(template.importTemplateId, request)
        : createImportTemplate(request)
    },
    onSuccess: (saved) => {
      toast.success(
        template ? `Saved "${saved.name}".` : `Created "${saved.name}".`
      )
      void queryClient.invalidateQueries({ queryKey: ["import-templates"] })
      onOpenChange(false)
    },
  })

  function move(key: string, delta: number) {
    const next = [...included]
    const from = next.indexOf(key)
    const to = from + delta
    if (to < 0 || to >= next.length) return
    ;[next[from], next[to]] = [next[to], next[from]]
    setColumns(next)
  }

  function chooseType(id: string) {
    setDocumentTypeId(id)
    setColumns(null)
    setHeaders({})
    setDefaults({})
  }

  const canSave =
    Boolean(documentTypeId) &&
    name.trim().length > 0 &&
    uncovered.length === 0 &&
    !save.isPending

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => !save.isPending && onOpenChange(next)}
    >
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[720px]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle>
            {template ? `Edit "${template.name}"` : "New import template"}
          </SheetTitle>
          <SheetDescription>
            Choose the columns people fill in and set defaults for the rest.
            File name and Title are always the first two columns.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-5 overflow-y-auto p-4">
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <label htmlFor="template-name" className={LABEL}>
                Name
              </label>
              <Input
                id="template-name"
                value={name}
                maxLength={150}
                onChange={(e) => setName(e.target.value)}
              />
            </div>
            <div>
              <span className={LABEL}>Document type</span>
              {template ? (
                <p className="py-2 text-[13.5px]">
                  {template.documentTypeName ?? "—"}
                </p>
              ) : (
                <SearchableSelect
                  options={documentTypes.map((t) => ({
                    value: t.documentTypeId,
                    label: t.name,
                  }))}
                  selected={documentTypeId}
                  onChange={chooseType}
                  allLabel="Select a document type"
                  className="w-full"
                />
              )}
            </div>
            <div className="md:col-span-2">
              <label htmlFor="template-description" className={LABEL}>
                Description <span className="normal-case">(optional)</span>
              </label>
              <Textarea
                id="template-description"
                rows={2}
                maxLength={500}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
          </div>

          {!documentTypeId ? (
            <p className="text-[13px] text-muted-foreground">
              Choose a document type to pick its fields.
            </p>
          ) : fieldsQuery.isPending ? (
            <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              Loading fields…
            </p>
          ) : fieldsQuery.isError ? (
            <InlineError>
              {friendlyMessage(fieldsQuery.error, "Could not load the fields.")}
            </InlineError>
          ) : (
            <>
              <section>
                <h3 className="mb-2 text-[13px] font-semibold">
                  Columns, in file order
                </h3>
                {template && template.missingFields.length > 0 && (
                  <p className="mb-2 text-[12.5px] text-muted-foreground">
                    No longer on the form, so dropped on save:{" "}
                    {template.missingFields.join(", ")}.
                  </p>
                )}
                {included.length === 0 ? (
                  <p className="rounded-md border border-dashed border-border px-3.5 py-3 text-[12.5px] text-muted-foreground">
                    Only File name and Title - add columns below.
                  </p>
                ) : (
                  <ul className="divide-y divide-border rounded-md border border-border">
                    {included.map((key, index) => {
                      const field = byKey.get(key)!
                      return (
                        <li
                          key={key}
                          className="grid items-center gap-2 px-3 py-2 md:grid-cols-[1fr_1fr_1fr_auto]"
                        >
                          <FieldName field={field} />
                          <Input
                            aria-label={`Column header for ${field.label}`}
                            placeholder={field.label}
                            maxLength={100}
                            value={headers[key] ?? ""}
                            onChange={(e) =>
                              setHeaders({ ...headers, [key]: e.target.value })
                            }
                            className="h-8 text-[13px]"
                          />
                          <DefaultInput
                            field={field}
                            value={defaults[key] ?? ""}
                            onChange={(value) =>
                              setDefaults({ ...defaults, [key]: value })
                            }
                            placeholder="Default if blank"
                          />
                          <div className="flex gap-1">
                            <IconAction
                              label="Move up"
                              disabled={index === 0}
                              onClick={() => move(key, -1)}
                            >
                              <ArrowUp size={14} />
                            </IconAction>
                            <IconAction
                              label="Move down"
                              disabled={index === included.length - 1}
                              onClick={() => move(key, 1)}
                            >
                              <ArrowDown size={14} />
                            </IconAction>
                            <IconAction
                              label="Remove column"
                              onClick={() =>
                                setColumns(included.filter((k) => k !== key))
                              }
                            >
                              <X size={14} />
                            </IconAction>
                          </div>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </section>

              {excluded.length > 0 && (
                <section>
                  <h3 className="mb-1 text-[13px] font-semibold">
                    Not in the file
                  </h3>
                  <p className="mb-2 text-[12.5px] text-muted-foreground">
                    These fields get the default you set here on every row (or
                    stay empty).
                  </p>
                  <ul className="divide-y divide-border rounded-md border border-border">
                    {excluded.map((field) => (
                      <li
                        key={field.fieldKey}
                        className="grid items-center gap-2 px-3 py-2 md:grid-cols-[1fr_1fr_auto]"
                      >
                        <FieldName field={field} />
                        <DefaultInput
                          field={field}
                          value={defaults[field.fieldKey] ?? ""}
                          onChange={(value) =>
                            setDefaults({
                              ...defaults,
                              [field.fieldKey]: value,
                            })
                          }
                          placeholder="Default value"
                        />
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            setColumns([...included, field.fieldKey])
                          }
                        >
                          <Plus />
                          Add column
                        </Button>
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {uncovered.length > 0 && (
                <InlineError>
                  Required fields need a column or a default:{" "}
                  {uncovered.map((f) => f.label).join(", ")}.
                </InlineError>
              )}
            </>
          )}
          {save.isError && (
            <InlineError>
              {friendlyMessage(save.error, "Could not save the template.")}
            </InlineError>
          )}
        </div>

        <SheetFooter className="border-t border-border">
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={save.isPending}
            >
              Cancel
            </Button>
            <Button
              onClick={() => save.mutate()}
              disabled={!canSave}
              className="flex-1"
            >
              {save.isPending && <Loader2 className="animate-spin" />}
              {template ? "Save changes" : "Create template"}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function FieldName({ field }: { field: ImportTemplateFieldDto }) {
  return (
    <div className="min-w-0">
      <p className="truncate text-[13px] font-medium">
        {field.label}
        {field.mandatory && (
          <Badge variant="gold" className="ml-2 rounded-md">
            Required
          </Badge>
        )}
      </p>
      <p className="truncate font-mono text-[11.5px] text-muted-foreground">
        {field.fieldKey}
      </p>
    </div>
  )
}

function DefaultInput({
  field,
  value,
  onChange,
  placeholder,
}: {
  field: ImportTemplateFieldDto
  value: string
  onChange: (value: string) => void
  placeholder: string
}) {
  if (field.dataType === "dropdown" || field.dataType === "boolean") {
    const options =
      field.dataType === "boolean" ? ["true", "false"] : field.options
    return (
      <SearchableSelect
        options={options.map((option) => ({ value: option, label: option }))}
        selected={value}
        onChange={onChange}
        allLabel={placeholder}
        searchable={options.length > 8}
        className="w-full"
      />
    )
  }
  return (
    <Input
      aria-label={`Default for ${field.label}`}
      type={field.dataType === "date" ? "date" : "text"}
      placeholder={
        field.dataType === "multi_select"
          ? `${placeholder} (separate with ;)`
          : placeholder
      }
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="h-8 text-[13px]"
    />
  )
}

function IconAction({
  label,
  onClick,
  disabled,
  children,
}: {
  label: string
  onClick: () => void
  disabled?: boolean
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      disabled={disabled}
      className="flex size-7 items-center justify-center rounded-md border border-border text-muted-foreground hover:text-foreground disabled:opacity-40"
    >
      {children}
    </button>
  )
}
