"use client"

import type { ReactNode } from "react"
import { Loader2, type LucideIcon } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { cn } from "@/lib/utils"

interface WorkflowFormDialogProps {
  icon: LucideIcon
  tone?: "primary" | "gold" | "rust"
  title: string
  description: ReactNode
  confirmLabel: string
  canConfirm: boolean
  submitting: boolean
  onConfirm: () => void
  onOpenChange: (open: boolean) => void
  children: ReactNode
}

const TONE = {
  primary: { icon: "bg-primary/10 text-primary", button: "border-primary bg-primary text-primary-foreground" },
  gold: { icon: "bg-[var(--gold-tint)] text-[var(--gold-foreground)]", button: "border-primary bg-primary text-primary-foreground" },
  rust: { icon: "bg-[var(--rust-tint)] text-[var(--rust)]", button: "border-[var(--rust)] bg-[var(--rust)] text-white" },
}

/** The shell every Phase 2 workflow dialog shares - same look as TaskActionDialog and the cancel dialog. */
export function WorkflowFormDialog({
  icon: Icon,
  tone = "primary",
  title,
  description,
  confirmLabel,
  canConfirm,
  submitting,
  onConfirm,
  onOpenChange,
  children,
}: WorkflowFormDialogProps) {
  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-xl">
          <span className={cn("mx-auto mb-4 flex size-11 items-center justify-center rounded-btn", TONE[tone].icon)}>
            <Icon size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
          <div className="mb-5 flex flex-col gap-4 text-left">{children}</div>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              type="button"
              disabled={submitting}
              className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
            >
              Cancel
            </AlertDialogClose>
            <button
              type="button"
              onClick={onConfirm}
              disabled={!canConfirm || submitting}
              className={cn(
                "flex items-center gap-1.5 border disabled:cursor-not-allowed disabled:opacity-50 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center",
                TONE[tone].button
              )}
            >
              {submitting && <Loader2 size={14} className="animate-spin" />}
              {confirmLabel}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
