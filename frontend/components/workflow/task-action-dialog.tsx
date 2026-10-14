"use client"

import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { CheckCircle2, Loader2, XCircle } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import { approveSchema, rejectSchema, type TaskDecisionFormValues } from "./schema"

export type TaskDecisionKind = "approve" | "reject"

const CONFIG = {
  approve: {
    icon: CheckCircle2,
    iconClassName: "bg-primary/10 text-primary",
    title: "Approve this document?",
    label: "Comment (optional)",
    placeholder: "Anything the submitter or the next approver should know…",
    confirmLabel: "Approve",
    confirmClassName: "border-primary bg-primary text-primary-foreground",
  },
  reject: {
    icon: XCircle,
    iconClassName: "bg-[var(--rust-tint)] text-[var(--rust)]",
    title: "Reject this document?",
    label: "Reason for rejection (required)",
    placeholder: "What needs to change before this can be approved…",
    confirmLabel: "Reject",
    confirmClassName: "border-[var(--rust)] bg-[var(--rust)] text-white",
  },
} as const

interface TaskActionDialogProps {
  decision: TaskDecisionKind
  documentTitle: string
  /** e.g. "Stage 2 of 3: Legal review". */
  stageLabel: string
  versionChanged?: boolean
  onOpenChange: (open: boolean) => void
  /** Resolves true when the decision was recorded - the dialog then closes; on false it stays open. */
  onConfirm: (text: string) => Promise<boolean>
}

/**
 * Approve (optional comment) or Reject (mandatory reason, WA-FR-003) one
 * task. Validated with zod - a reject reason must have at least one
 * non-blank character, and both are capped at 2000 like the backend. The
 * caller mounts this only while a decision is being made, so every opening
 * starts with an empty form.
 */
export function TaskActionDialog({ decision, documentTitle, stageLabel, versionChanged, onOpenChange, onConfirm }: TaskActionDialogProps) {
  const config = CONFIG[decision]
  const Icon = config.icon

  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<TaskDecisionFormValues>({
    resolver: zodResolver(decision === "reject" ? rejectSchema : approveSchema),
    defaultValues: { text: "" },
  })

  async function submit(values: TaskDecisionFormValues) {
    const ok = await onConfirm(values.text.trim())
    if (ok) onOpenChange(false)
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-xl">
          <form onSubmit={handleSubmit(submit)}>
            <span className={cn("mx-auto mb-4 flex size-11 items-center justify-center rounded-md", config.iconClassName)}>
              <Icon size={20} aria-hidden="true" />
            </span>
            <AlertDialogTitle>{config.title}</AlertDialogTitle>
            <AlertDialogDescription>
              <strong className="font-semibold text-foreground">{documentTitle}</strong>
              <br />
              <span className="text-[13.5px]">{stageLabel}</span>
            </AlertDialogDescription>

            {versionChanged && (
              <p className="mb-4 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-left text-[12.5px] text-[var(--gold-foreground)]">
                A newer version of this document was uploaded after it was submitted. You are deciding on the submitted
                version.
              </p>
            )}

            <div className="mb-5 text-left">
              <Label htmlFor={`task-${decision}-text`}>{config.label}</Label>
              <Textarea
                id={`task-${decision}-text`}
                rows={3}
                maxLength={2000}
                placeholder={config.placeholder}
                aria-invalid={Boolean(errors.text)}
                {...register("text")}
              />
              {errors.text && <p className="mt-1.5 text-[12px] text-destructive">{errors.text.message}</p>}
            </div>

            <div className="flex items-center justify-center gap-2.5">
              <AlertDialogClose
                type="button"
                disabled={isSubmitting}
                className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="submit"
                disabled={isSubmitting}
                className={cn(
                  "flex items-center gap-1.5 rounded-md border px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] disabled:cursor-not-allowed disabled:opacity-60",
                  config.confirmClassName
                )}
              >
                {isSubmitting && <Loader2 size={14} className="animate-spin" />}
                {config.confirmLabel}
              </button>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
