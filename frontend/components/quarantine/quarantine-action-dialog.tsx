"use client"

import { useState } from "react"
import { ShieldCheck, ShieldOff, Trash2, type LucideIcon } from "lucide-react"

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

/** Matches the backend's QuarantineActionRequest / AuditReasonSanitizer limit. */
const REASON_MAX_LENGTH = 1000

export type QuarantineAction = "release" | "override" | "delete"

const ACTION_CONFIG = {
  release: {
    icon: ShieldCheck,
    iconClassName: "bg-primary/10 text-primary",
    title: "Release this file?",
    description: (filename: string) => (
      <>
        <strong className="font-semibold text-foreground">{filename}</strong> will be re-scanned against the
        current malware definitions. It is released into the repository only if it now comes back clean;
        otherwise it stays in quarantine.
      </>
    ),
    reasonLabel: "Why do you believe this is a false positive?",
    placeholder: "e.g. Confirmed with the vendor, the detection has since been corrected…",
    confirmLabel: "Re-scan and Release",
    confirmClassName: "border-primary bg-primary text-primary-foreground",
  },
  override: {
    icon: ShieldOff,
    iconClassName: "bg-[var(--gold-tint)] text-[var(--gold-foreground)]",
    title: "Force-release despite the scanner?",
    description: (filename: string) => (
      <>
        The scanner <strong className="font-semibold text-foreground">still flags</strong>{" "}
        <strong className="font-semibold text-foreground">{filename}</strong>. Overriding releases it into the
        repository anyway. This bypasses a security control and is recorded as a high-severity audit event
        under your name.
      </>
    ),
    reasonLabel: "Justification for overriding the scan",
    placeholder: "e.g. Verified benign by the security team (ticket SEC-123)…",
    confirmLabel: "Override and Release",
    confirmClassName: "border-[var(--gold-foreground)] bg-[var(--gold-foreground)] text-white",
  },
  delete: {
    icon: Trash2,
    iconClassName: "bg-[var(--rust-tint)] text-[var(--rust)]",
    title: "Permanently delete this file?",
    description: (filename: string) => (
      <>
        <strong className="font-semibold text-foreground">{filename}</strong> will be removed from quarantine
        storage for good. The quarantine record and its audit history are kept.
      </>
    ),
    reasonLabel: "Reason for deletion",
    placeholder: "e.g. Confirmed malicious, reported to the uploader's manager…",
    confirmLabel: "Delete Permanently",
    confirmClassName: "border-[var(--rust)] bg-[var(--rust)] text-white",
  },
} as const satisfies Record<
  QuarantineAction,
  {
    icon: LucideIcon
    iconClassName: string
    title: string
    description: (filename: string) => React.ReactNode
    reasonLabel: string
    placeholder: string
    confirmLabel: string
    confirmClassName: string
  }
>

interface QuarantineActionDialogProps {
  action: QuarantineAction
  filename: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (reason: string) => void
}

/**
 * One confirmation dialog for all three quarantine review actions (release,
 * override, delete), modelled on UserStatusDialog: per-action copy comes
 * from ACTION_CONFIG, and the confirm button stays disabled until a reason
 * is entered - every one of these actions requires one (the backend rejects
 * a blank reason, and the audit event records it).
 */
export function QuarantineActionDialog({ action, filename, open, onOpenChange, onConfirm }: QuarantineActionDialogProps) {
  const config = ACTION_CONFIG[action]
  const Icon = config.icon

  // Starts empty on every open: the caller mounts this dialog only while an
  // action is chosen, so no reset effect is needed.
  const [reason, setReason] = useState("")

  const trimmedReason = reason.trim()

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-2xl">
          <span className={cn("mx-auto mb-4 flex size-11 items-center justify-center rounded-btn", config.iconClassName)}>
            <Icon size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>{config.title}</AlertDialogTitle>
          <AlertDialogDescription>{config.description(filename)}</AlertDialogDescription>

          <div className="mb-5 text-left">
            <Label htmlFor={`quarantine-${action}-reason`}>{config.reasonLabel}</Label>
            <Textarea
              id={`quarantine-${action}-reason`}
              rows={3}
              maxLength={REASON_MAX_LENGTH}
              placeholder={config.placeholder}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={() => onConfirm(trimmedReason)}
              disabled={trimmedReason.length === 0}
              className={cn(
                "border h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center",
                "disabled:cursor-not-allowed disabled:opacity-50",
                config.confirmClassName
              )}
            >
              {config.confirmLabel}
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
