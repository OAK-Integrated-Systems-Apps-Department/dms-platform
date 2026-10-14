"use client"

import { useMemo, useState } from "react"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm, useWatch } from "react-hook-form"
import { AlertTriangle, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
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
import {
  WATERMARK_PAGE_SCOPES,
  WATERMARK_PLACEMENTS,
  WATERMARK_STYLE_DEFAULTS,
  WATERMARK_STYLE_LIMITS,
  WATERMARK_TEMPLATE_LABEL_MAX_LENGTH,
  WATERMARK_TEXT_MAX_LENGTH,
} from "@/lib/watermarks/constants"

import { PlaceholderChips } from "./placeholder-chips"
import { WatermarkPreview } from "./watermark-preview"
import { watermarkTemplateSchema, type WatermarkTemplateFormValues } from "./schema"

import type { WatermarkTemplateDto } from "@/lib/watermarks/api"

interface WatermarkTemplateFormProps {
  /** Null = create. */
  template: WatermarkTemplateDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Resolves to an error message to show, or null on success. */
  onSubmit: (values: WatermarkTemplateFormValues) => Promise<FormFailure | null>
}

export interface FormFailure {
  message: string
  /** Set when the server blamed one field - e.g. a duplicate label. */
  field?: "label" | "defaultText"
}

/** "" in a <select> means "inherit the system default" - the backend wants null for that. */
const INHERIT = ""

function toForm(template: WatermarkTemplateDto | null): WatermarkTemplateFormValues {
  return {
    label: template?.label ?? "",
    defaultText: template?.defaultText ?? "",
    active: template?.active ?? true,
    fontSize: template?.fontSize ?? null,
    opacity: template?.opacity ?? null,
    rotation: template?.rotation ?? null,
    color: template?.color ?? null,
    placement: (template?.placement as WatermarkTemplateFormValues["placement"]) ?? null,
    pageScope: (template?.pageScope as WatermarkTemplateFormValues["pageScope"]) ?? null,
  }
}

/**
 * Create or edit one tenant template.
 *
 * <h2>Blank means "inherit", not zero</h2>
 * Every style field is optional: a template may set only what it cares
 * about and leave the rest to the system defaults. That makes an empty
 * number input meaningful - it has to become `null`, not `0`, or saving a
 * template with no opinion on opacity would pin it to an invalid 0. See
 * {@link blank} for why that conversion cannot just trust `Number()`.
 *
 * <h2>Built-in templates never reach here</h2>
 * The four system templates are read-only; the list doesn't offer an edit
 * control for them and the backend answers 403 regardless. This form is
 * only ever opened for a tenant's own template, or for a new one.
 */
export function WatermarkTemplateForm({
  template,
  open,
  onOpenChange,
  onSubmit,
}: WatermarkTemplateFormProps) {
  const [submitError, setSubmitError] = useState<string | null>(null)
  const editing = template !== null

  const {
    register,
    handleSubmit,
    control,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<WatermarkTemplateFormValues>({
    resolver: zodResolver(watermarkTemplateSchema),
    defaultValues: toForm(template),
    mode: "onChange",
  })

  // useWatch by name, matching role-form/permission-form: a bare `watch()`
  // opts the component out of React Compiler memoization.
  const [defaultText, fontSize, opacity, rotation, color, placement, pageScope] = useWatch({
    control,
    name: ["defaultText", "fontSize", "opacity", "rotation", "color", "placement", "pageScope"],
  })

  const previewRequest = useMemo(
    () => ({
      customText: defaultText.trim() || null,
      fontSize,
      opacity,
      rotation,
      color,
      placement,
      pageScope,
    }),
    [
      defaultText,
      fontSize,
      opacity,
      rotation,
      color,
      placement,
      pageScope,
    ]
  )

  async function handleFormSubmit(form: WatermarkTemplateFormValues) {
    setSubmitError(null)
    const failure = await onSubmit(form)
    if (!failure) return
    if (failure.field) {
      setError(failure.field, { type: "server", message: failure.message })
    } else {
      setSubmitError(failure.message)
    }
  }

  /**
   * Empty input -> null (inherit).
   *
   * The emptiness has to be tested BEFORE any conversion, and against every
   * shape an untouched field reports. A number input that has never been
   * typed in reads back as `null` here, not `""` or `NaN`, and `Number(null)`
   * is 0 - which would silently turn "no opinion on font size" into "font
   * size zero" and make the server refuse the preview. Same trap for text:
   * `String(null)` is the string "null".
   */
  function blank(raw: unknown): boolean {
    return raw === "" || raw === null || raw === undefined
  }

  function numberOrNull(raw: unknown): number | null {
    if (blank(raw)) return null
    const value = Number(raw)
    return Number.isNaN(value) ? null : value
  }

  function textOrNull(raw: unknown): string | null {
    return blank(raw) ? null : String(raw)
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[560px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>{editing ? "Edit template" : "New watermark template"}</SheetTitle>
          <SheetDescription>
            Style fields left blank use the system default, so a template can set only what it
            cares about.
          </SheetDescription>
        </SheetHeader>

        <form onSubmit={handleSubmit(handleFormSubmit)} className="flex min-h-0 flex-1 flex-col" noValidate>
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

            <div>
              <Label htmlFor="template-label">Name</Label>
              <Input
                id="template-label"
                maxLength={WATERMARK_TEMPLATE_LABEL_MAX_LENGTH}
                placeholder="e.g. Client copy"
                aria-invalid={Boolean(errors.label)}
                {...register("label")}
              />
              {errors.label && (
                <p className="mt-1.5 text-[12px] text-destructive">{errors.label.message}</p>
              )}
            </div>

            <div>
              <LabelWithHint
                htmlFor="template-text"
                hint={`Up to ${WATERMARK_TEXT_MAX_LENGTH} characters. Placeholders are filled in when someone uses this template.`}
              >
                Watermark text
              </LabelWithHint>
              <Textarea
                id="template-text"
                rows={3}
                placeholder="CONFIDENTIAL - {username}"
                aria-invalid={Boolean(errors.defaultText)}
                {...register("defaultText")}
              />
              {errors.defaultText && (
                <p className="mt-1.5 text-[12px] text-destructive">{errors.defaultText.message}</p>
              )}
              <div className="mt-2.5">
                <PlaceholderChips
                  onInsert={(token) =>
                    setValue("defaultText", `${defaultText}${token}`, { shouldValidate: true })
                  }
                />
              </div>
            </div>

            <div>
              <Label htmlFor="template-status">Status</Label>
              <Controller
                name="active"
                control={control}
                render={({ field }) => (
                  <SegmentedControl
                    value={field.value ? "active" : "inactive"}
                    onChange={(value) => field.onChange(value === "active")}
                    options={[
                      { value: "active", label: "Active" },
                      { value: "inactive", label: "Retired" },
                    ]}
                  />
                )}
              />
              <p className="mt-1.5 text-[11.5px] leading-[1.5] text-muted-foreground">
                A retired template stays on copies already made with it, but nobody can pick it for a
                new one.
              </p>
            </div>

            <fieldset className="grid grid-cols-2 gap-3 rounded-lg border border-border p-3.5">
              <legend className="px-1.5 text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">
                Default style
              </legend>

              <div>
                <Label htmlFor="template-font-size">
                  Font size{" "}
                  <span className="normal-case text-[var(--ink-faint)]">
                    (blank = {WATERMARK_STYLE_DEFAULTS.fontSize})
                  </span>
                </Label>
                <Input
                  id="template-font-size"
                  type="number"
                  min={WATERMARK_STYLE_LIMITS.fontSize.min}
                  max={WATERMARK_STYLE_LIMITS.fontSize.max}
                  aria-invalid={Boolean(errors.fontSize)}
                  {...register("fontSize", { setValueAs: numberOrNull })}
                />
                {errors.fontSize && (
                  <p className="mt-1.5 text-[12px] text-destructive">{errors.fontSize.message}</p>
                )}
              </div>

              <div>
                <Label htmlFor="template-opacity">
                  Opacity %{" "}
                  <span className="normal-case text-[var(--ink-faint)]">
                    (blank = {WATERMARK_STYLE_DEFAULTS.opacity})
                  </span>
                </Label>
                <Input
                  id="template-opacity"
                  type="number"
                  min={WATERMARK_STYLE_LIMITS.opacity.min}
                  max={WATERMARK_STYLE_LIMITS.opacity.max}
                  aria-invalid={Boolean(errors.opacity)}
                  {...register("opacity", { setValueAs: numberOrNull })}
                />
                {errors.opacity && (
                  <p className="mt-1.5 text-[12px] text-destructive">{errors.opacity.message}</p>
                )}
              </div>

              <div>
                <Label htmlFor="template-rotation">
                  Rotation °{" "}
                  <span className="normal-case text-[var(--ink-faint)]">
                    (blank = {WATERMARK_STYLE_DEFAULTS.rotation})
                  </span>
                </Label>
                <Input
                  id="template-rotation"
                  type="number"
                  min={WATERMARK_STYLE_LIMITS.rotation.min}
                  max={WATERMARK_STYLE_LIMITS.rotation.max}
                  aria-invalid={Boolean(errors.rotation)}
                  {...register("rotation", { setValueAs: numberOrNull })}
                />
                {errors.rotation && (
                  <p className="mt-1.5 text-[12px] text-destructive">{errors.rotation.message}</p>
                )}
              </div>

              <div>
                <Label htmlFor="template-color">
                  Colour{" "}
                  <span className="normal-case text-[var(--ink-faint)]">
                    (blank = {WATERMARK_STYLE_DEFAULTS.color})
                  </span>
                </Label>
                <div className="flex items-center gap-2">
                  <Input
                    id="template-color"
                    placeholder={WATERMARK_STYLE_DEFAULTS.color}
                    aria-invalid={Boolean(errors.color)}
                    {...register("color", { setValueAs: textOrNull })}
                  />
                  <input
                    type="color"
                    aria-label="Pick watermark colour"
                    className="size-7 shrink-0 cursor-pointer rounded-btn border border-border bg-transparent p-0"
                    value={
                      color && /^#[0-9a-fA-F]{6}$/.test(color)
                        ? color
                        : WATERMARK_STYLE_DEFAULTS.color
                    }
                    onChange={(event) =>
                      setValue("color", event.target.value, { shouldValidate: true })
                    }
                  />
                </div>
                {errors.color && (
                  <p className="mt-1.5 text-[12px] text-destructive">{errors.color.message}</p>
                )}
              </div>

              <div>
                <Label htmlFor="template-placement">Placement</Label>
                <Controller
                  name="placement"
                  control={control}
                  render={({ field }) => (
                    <SearchableSelect
                      selected={field.value ?? INHERIT}
                      onChange={(value) =>
                        field.onChange(value === INHERIT ? null : (value as typeof field.value))
                      }
                      allLabel={`Default (${WATERMARK_STYLE_DEFAULTS.placement})`}
                      searchable={false}
                      options={WATERMARK_PLACEMENTS.map((p) => ({ value: p.value, label: p.label }))}
                      className="w-full"
                    />
                  )}
                />
              </div>

              <div>
                <Label htmlFor="template-scope">Pages</Label>
                <Controller
                  name="pageScope"
                  control={control}
                  render={({ field }) => (
                    <SearchableSelect
                      selected={field.value ?? INHERIT}
                      onChange={(value) =>
                        field.onChange(value === INHERIT ? null : (value as typeof field.value))
                      }
                      allLabel={`Default (${WATERMARK_STYLE_DEFAULTS.pageScope})`}
                      searchable={false}
                      options={WATERMARK_PAGE_SCOPES.map((s) => ({ value: s.value, label: s.label }))}
                      className="w-full"
                    />
                  )}
                />
              </div>
            </fieldset>

            <WatermarkPreview
              request={previewRequest}
              enabled={defaultText.trim().length > 0}
            />
          </div>

          <SheetFooter className="border-t border-border">
            <div className="flex items-center justify-end gap-2.5">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
                {editing ? "Save template" : "Create template"}
              </Button>
            </div>
          </SheetFooter>
        </form>
      </SheetContent>
    </Sheet>
  )
}
