"use client"

import { useMemo, useRef, useState } from "react"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm, useWatch } from "react-hook-form"
import { useQuery } from "@tanstack/react-query"
import { AlertTriangle, ChevronDown, Droplet, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LabelWithHint } from "@/components/ui/label-with-hint"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Textarea } from "@/components/ui/textarea"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchDocumentVersions } from "@/lib/documents/api"
import { requestWatermark } from "@/lib/watermarks/api"
import {
  FIRST_PAGE_ONLY_BY_FORMAT,
  WATERMARK_LIMITATIONS,
  WATERMARK_OUTPUT_TTL_HINT,
  WATERMARK_PAGE_SCOPES,
  WATERMARK_PLACEMENTS,
  WATERMARK_STYLE_DEFAULTS,
  WATERMARK_STYLE_LIMITS,
  WATERMARK_TEXT_MAX_LENGTH,
} from "@/lib/watermarks/constants"
import { describeWatermarkError } from "@/lib/watermarks/watermark-errors"
import { cn } from "@/lib/utils"

import { PlaceholderChips } from "./placeholder-chips"
import { WatermarkJobTracker } from "./watermark-job-tracker"
import { WatermarkPreview } from "./watermark-preview"
import { watermarkRequestSchema, type WatermarkRequestFormValues } from "./schema"

import type { WatermarkJobDto, WatermarkTemplateDto } from "@/lib/watermarks/api"

interface WatermarkDialogProps {
  documentId: string
  documentTitle: string
  templates: WatermarkTemplateDto[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

function defaultValues(templates: WatermarkTemplateDto[]): WatermarkRequestFormValues {
  return {
    source: templates.length > 0 ? "template" : "custom",
    templateId: templates[0]?.templateId ?? "",
    customText: "",
    versionId: "",
    ...WATERMARK_STYLE_DEFAULTS,
  }
}

/**
 * Ask for a watermarked copy of one document.
 *
 * <h2>Template OR text, never both</h2>
 * The backend requires exactly one, so this is a mode switch rather than two
 * fields that happen to be mutually exclusive. Only the active control is
 * rendered: showing both greyed-out invites filling in the wrong one and
 * turns a clear choice into a puzzle.
 *
 * <h2>The idempotency key is per dialog opening</h2>
 * Generated when the dialog opens and reused for every attempt within it, so
 * a double-click, or a retry after a dropped connection, returns the SAME
 * job rather than producing a second copy of somebody's document. A fresh
 * open means a genuinely new request and gets a fresh key, which the caller
 * guarantees by mounting this component per opening and keying it.
 *
 * <h2>The version selector is permission-gated</h2>
 * Naming a `versionId` needs `dvc.view-version-history.view` on the backend
 * too. Without it the field is not rendered AND no versionId is sent -
 * sending one would be a 403 the user could do nothing about, and the
 * current version is the right default anyway.
 */
export function WatermarkDialog({
  documentId,
  documentTitle,
  templates,
  open,
  onOpenChange,
}: WatermarkDialogProps) {
  const { hasPermission } = usePermissions()
  const canChooseVersion = hasPermission(PermissionCodes.DVC_VERSIONS_VIEW)

  const [submitError, setSubmitError] = useState<string | null>(null)
  const [unrenderable, setUnrenderable] = useState<string | null>(null)
  const [job, setJob] = useState<WatermarkJobDto | null>(null)
  const textRef = useRef<HTMLTextAreaElement | null>(null)

  const activeTemplates = useMemo(() => templates.filter((t) => t.active), [templates])

  const {
    register,
    handleSubmit,
    control,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<WatermarkRequestFormValues>({
    resolver: zodResolver(watermarkRequestSchema),
    defaultValues: defaultValues(activeTemplates),
    mode: "onChange",
  })

  /**
   * One key per opening - see the class note.
   *
   * This is per MOUNT, which is the same thing: the caller mounts the
   * dialog only while it is open and keys it per opening, so there is no
   * state to reset and no effect to do the resetting. Generating it in a
   * lazy initialiser rather than an effect also means it exists before the
   * first render that could submit.
   */
  const [idempotencyKey] = useState(() => crypto.randomUUID())

  // useWatch by name, matching role-form/permission-form. A bare `watch()`
  // returns a value the React Compiler can't memoize and so opts the whole
  // component out of compilation; the name-array form stays fully typed.
  const [
    source,
    templateId,
    customText,
    fontSize,
    opacity,
    rotation,
    color,
    placement,
    pageScope,
  ] = useWatch({
    control,
    name: [
      "source",
      "templateId",
      "customText",
      "fontSize",
      "opacity",
      "rotation",
      "color",
      "placement",
      "pageScope",
    ],
  })
  const usingTemplate = source === "template"

  const { data: versions } = useQuery({
    queryKey: ["documents", documentId, "versions", "watermark"],
    queryFn: () => fetchDocumentVersions(documentId),
    enabled: open && canChooseVersion,
  })

  /** What the preview endpoint is asked for - the same style the real request will carry. */
  const previewRequest = useMemo(
    () => ({
      templateId: usingTemplate ? templateId || null : null,
      customText: usingTemplate ? null : customText.trim() || null,
      fontSize,
      opacity,
      rotation,
      color,
      placement,
      pageScope,
    }),
    [
      usingTemplate,
      templateId,
      customText,
      fontSize,
      opacity,
      rotation,
      color,
      placement,
      pageScope,
    ]
  )

  const previewReady = usingTemplate
    ? Boolean(templateId)
    : customText.trim().length > 0

  function insertPlaceholder(token: string) {
    const element = textRef.current
    const current = customText
    if (!element) {
      setValue("customText", `${current}${token}`, { shouldValidate: true })
      return
    }
    // Inserted at the caret rather than appended, so a placeholder can go in
    // the middle of a sentence - which is where most of them belong.
    const start = element.selectionStart ?? current.length
    const end = element.selectionEnd ?? current.length
    const next = `${current.slice(0, start)}${token}${current.slice(end)}`
    setValue("customText", next, { shouldValidate: true })
    requestAnimationFrame(() => {
      element.focus()
      const caret = start + token.length
      element.setSelectionRange(caret, caret)
    })
  }

  async function onSubmit(form: WatermarkRequestFormValues) {
    setSubmitError(null)
    try {
      const created = await requestWatermark(
        documentId,
        {
          templateId: form.source === "template" ? form.templateId : null,
          customText: form.source === "custom" ? form.customText.trim() : null,
          versionId: canChooseVersion && form.versionId ? form.versionId : null,
          fontSize: form.fontSize,
          opacity: form.opacity,
          rotation: form.rotation,
          color: form.color,
          placement: form.placement,
          pageScope: form.pageScope,
        },
        idempotencyKey
      )
      setJob(created)
    } catch (error) {
      const failure = describeWatermarkError(error)
      if (failure.field) {
        setError(failure.field, { type: "server", message: failure.message })
      } else {
        setSubmitError(failure.message)
      }
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[560px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2">
            <Droplet size={18} className="text-primary" aria-hidden="true" />
            Add watermark
          </SheetTitle>
          <SheetDescription className="truncate">{documentTitle}</SheetDescription>
        </SheetHeader>

        {job ? (
          <div className="min-h-0 flex-1 overflow-y-auto p-5">
            <WatermarkJobTracker job={job} onClose={() => onOpenChange(false)} />
          </div>
        ) : (
          <form
            onSubmit={handleSubmit(onSubmit)}
            className="flex min-h-0 flex-1 flex-col"
            noValidate
          >
            <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-5">
              {submitError && (
                <div
                  role="alert"
                  className="flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]"
                >
                  <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
                  <span>{submitError}</span>
                </div>
              )}

              {/* ---------------------------------------- what it says */}
              <section className="flex flex-col gap-3">
                <Controller
                  name="source"
                  control={control}
                  render={({ field }) => (
                    <SegmentedControl
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        { value: "template", label: "Use a template" },
                        { value: "custom", label: "Custom text" },
                      ]}
                      disabled={activeTemplates.length === 0}
                    />
                  )}
                />

                {usingTemplate ? (
                  <div>
                    <Label htmlFor="watermark-template">Template</Label>
                    <Controller
                      name="templateId"
                      control={control}
                      render={({ field }) => (
                        <SearchableSelect
                          selected={field.value}
                          onChange={field.onChange}
                          allLabel="Choose a template"
                          options={activeTemplates.map((template) => ({
                            value: template.templateId,
                            label: template.systemTemplate
                              ? `${template.label} (built-in)`
                              : template.label,
                          }))}
                          className="w-full"
                        />
                      )}
                    />
                    {errors.templateId && (
                      <p className="mt-1.5 text-[12px] text-destructive">{errors.templateId.message}</p>
                    )}
                  </div>
                ) : (
                  <div>
                    <LabelWithHint
                      htmlFor="watermark-text"
                      hint={`Up to ${WATERMARK_TEXT_MAX_LENGTH} characters. Once placeholders are filled in, very long text may still be refused as too wide for the page.`}
                    >
                      Watermark text
                    </LabelWithHint>
                    <Textarea
                      id="watermark-text"
                      rows={3}
                      placeholder="CONFIDENTIAL - {username}"
                      aria-invalid={Boolean(errors.customText)}
                      aria-describedby={errors.customText ? "watermark-text-error" : undefined}
                      {...register("customText")}
                      ref={(element) => {
                        register("customText").ref(element)
                        textRef.current = element
                      }}
                    />
                    {errors.customText && (
                      <p id="watermark-text-error" className="mt-1.5 text-[12px] text-destructive">
                        {errors.customText.message}
                      </p>
                    )}
                    <div className="mt-2.5">
                      <PlaceholderChips onInsert={insertPlaceholder} />
                    </div>
                  </div>
                )}
              </section>

              {/* ---------------------------------------- where it goes */}
              <section className="flex flex-col gap-3">
                <div>
                  <Label htmlFor="watermark-placement">Placement</Label>
                  <Controller
                    name="placement"
                    control={control}
                    render={({ field }) => (
                      <SearchableSelect
                        selected={field.value}
                        onChange={(value) => field.onChange(value || WATERMARK_STYLE_DEFAULTS.placement)}
                        allLabel="Placement"
                        searchable={false}
                        options={WATERMARK_PLACEMENTS.map((p) => ({ value: p.value, label: p.label }))}
                        className="w-full"
                      />
                    )}
                  />
                  <p className="mt-1.5 text-[11.5px] leading-[1.5] text-muted-foreground">
                    {WATERMARK_PLACEMENTS.find((p) => p.value === placement)?.hint}
                  </p>
                </div>

                <div>
                  <Label htmlFor="watermark-scope">Pages</Label>
                  <Controller
                    name="pageScope"
                    control={control}
                    render={({ field }) => (
                      <SearchableSelect
                        selected={field.value}
                        onChange={(value) => field.onChange(value || WATERMARK_STYLE_DEFAULTS.pageScope)}
                        allLabel="Pages"
                        searchable={false}
                        options={WATERMARK_PAGE_SCOPES.map((s) => ({ value: s.value, label: s.label }))}
                        className="w-full"
                      />
                    )}
                  />
                  {pageScope === "first-page-only" && (
                    <p className="mt-1.5 text-[11.5px] leading-[1.5] text-muted-foreground">
                      {FIRST_PAGE_ONLY_BY_FORMAT}
                    </p>
                  )}
                </div>

                {canChooseVersion && versions && versions.length > 1 && (
                  <div>
                    <LabelWithHint
                      htmlFor="watermark-version"
                      hint="Leave this on the current version unless you specifically need an older one."
                    >
                      Version
                    </LabelWithHint>
                    <Controller
                      name="versionId"
                      control={control}
                      render={({ field }) => (
                        <SearchableSelect
                          selected={field.value}
                          onChange={field.onChange}
                          allLabel="Current version"
                          options={versions.map((version) => ({
                            value: version.versionId,
                            label: `v${version.versionLabel} — ${version.originalFilename}`,
                          }))}
                          className="w-full"
                        />
                      )}
                    />
                  </div>
                )}
              </section>

              {/* ---------------------------------------- how it looks */}
              <Collapsible>
                <CollapsibleTrigger
                  render={(props) => (
                    <button
                      {...props}
                      type="button"
                      className="flex w-full items-center justify-between rounded-btn border border-border bg-muted/20 px-3.5 py-2.5 text-[12.5px] font-semibold text-foreground transition-colors hover:bg-muted/40"
                    >
                      Style
                      <ChevronDown size={14} aria-hidden="true" />
                    </button>
                  )}
                />
                <CollapsibleContent>
                  <div className="grid grid-cols-2 gap-3 pt-3">
                    <div>
                      <Label htmlFor="watermark-font-size">
                        Font size{" "}
                        <span className="normal-case text-[var(--ink-faint)]">
                          (default {WATERMARK_STYLE_DEFAULTS.fontSize}pt)
                        </span>
                      </Label>
                      <Input
                        id="watermark-font-size"
                        type="number"
                        min={WATERMARK_STYLE_LIMITS.fontSize.min}
                        max={WATERMARK_STYLE_LIMITS.fontSize.max}
                        aria-invalid={Boolean(errors.fontSize)}
                        {...register("fontSize", { valueAsNumber: true })}
                      />
                      {errors.fontSize && (
                        <p className="mt-1.5 text-[12px] text-destructive">{errors.fontSize.message}</p>
                      )}
                    </div>
                    <div>
                      <Label htmlFor="watermark-opacity">
                        Opacity %{" "}
                        <span className="normal-case text-[var(--ink-faint)]">
                          (default {WATERMARK_STYLE_DEFAULTS.opacity})
                        </span>
                      </Label>
                      <Input
                        id="watermark-opacity"
                        type="number"
                        min={WATERMARK_STYLE_LIMITS.opacity.min}
                        max={WATERMARK_STYLE_LIMITS.opacity.max}
                        aria-invalid={Boolean(errors.opacity)}
                        {...register("opacity", { valueAsNumber: true })}
                      />
                      {errors.opacity && (
                        <p className="mt-1.5 text-[12px] text-destructive">{errors.opacity.message}</p>
                      )}
                    </div>
                    <div>
                      <Label htmlFor="watermark-rotation">
                        Rotation °{" "}
                        <span className="normal-case text-[var(--ink-faint)]">
                          (default {WATERMARK_STYLE_DEFAULTS.rotation})
                        </span>
                      </Label>
                      <Input
                        id="watermark-rotation"
                        type="number"
                        min={WATERMARK_STYLE_LIMITS.rotation.min}
                        max={WATERMARK_STYLE_LIMITS.rotation.max}
                        aria-invalid={Boolean(errors.rotation)}
                        {...register("rotation", { valueAsNumber: true })}
                      />
                      {errors.rotation && (
                        <p className="mt-1.5 text-[12px] text-destructive">{errors.rotation.message}</p>
                      )}
                    </div>
                    <div>
                      <Label htmlFor="watermark-color">
                        Colour{" "}
                        <span className="normal-case text-[var(--ink-faint)]">
                          (default {WATERMARK_STYLE_DEFAULTS.color})
                        </span>
                      </Label>
                      <div className="flex items-center gap-2">
                        <Input
                          id="watermark-color"
                          placeholder={WATERMARK_STYLE_DEFAULTS.color}
                          aria-invalid={Boolean(errors.color)}
                          {...register("color")}
                        />
                        <span
                          aria-hidden="true"
                          className="size-7 shrink-0 rounded-btn border border-border"
                          style={{
                            backgroundColor: /^#[0-9a-fA-F]{6}$/.test(color)
                              ? color
                              : "transparent",
                          }}
                        />
                      </div>
                      {errors.color && (
                        <p className="mt-1.5 text-[12px] text-destructive">{errors.color.message}</p>
                      )}
                    </div>
                  </div>
                </CollapsibleContent>
              </Collapsible>

              <WatermarkPreview
                request={previewRequest}
                enabled={previewReady}
                onUnrenderable={setUnrenderable}
              />

              <section className="rounded-btn border border-dashed border-border bg-muted/20 px-3.5 py-3">
                <h3 className="mb-1.5 text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">
                  Worth knowing
                </h3>
                <ul className="flex list-disc flex-col gap-1 pl-4 text-[11.5px] leading-[1.55] text-muted-foreground">
                  {WATERMARK_LIMITATIONS.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                  <li>The copy stays available for {WATERMARK_OUTPUT_TTL_HINT}.</li>
                </ul>
              </section>
            </div>

            <SheetFooter className="border-t border-border">
              {unrenderable && (
                <p className="mb-2 flex items-start gap-1.5 text-[12px] leading-[1.5] text-[var(--rust)]">
                  <AlertTriangle size={13} className="mt-0.5 shrink-0" aria-hidden="true" />
                  {unrenderable}
                </p>
              )}
              <div className="flex items-center justify-end gap-2.5">
                <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                  Cancel
                </Button>
                <Button
                  type="submit"
                  disabled={isSubmitting || Boolean(unrenderable)}
                  className={cn(isSubmitting && "cursor-wait")}
                >
                  {isSubmitting && (
                    <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                  )}
                  Create copy
                </Button>
              </div>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
