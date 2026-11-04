"use client"

import { useState } from "react"
import { CopyPlus, Loader2, Power, PowerOff, Trash2, type LucideIcon } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { cn } from "@/lib/utils"

import type { WorkflowTemplateDto } from "@/lib/workflow/api"

type TemplateAction = "activate" | "deactivate" | "delete" | "version"

const ACTION_CONFIG: Record<
  TemplateAction,
  {
    icon: LucideIcon
    iconClassName: string
    title: string
    description: (t: WorkflowTemplateDto) => React.ReactNode
    confirmLabel: string
    confirmClassName: string
  }
> = {
  activate: {
    icon: Power,
    iconClassName: "bg-primary/10 text-primary",
    title: "Activate this template?",
    description: (t) => (
      <>
        Version {t.versionNumber} of <strong className="font-semibold text-foreground">{t.name}</strong> becomes the
        approval workflow for every <strong className="font-semibold text-foreground">{t.documentTypeName ?? "document"}</strong>{" "}
        submitted from now on. It can no longer be edited; create a new version to change it. Workflows already running
        keep the version they started on.
      </>
    ),
    confirmLabel: "Activate",
    confirmClassName: "border-primary bg-primary text-primary-foreground",
  },
  deactivate: {
    icon: PowerOff,
    iconClassName: "bg-[var(--gold-tint)] text-[var(--gold-foreground)]",
    title: "Deactivate this template?",
    description: (t) => (
      <>
        New <strong className="font-semibold text-foreground">{t.documentTypeName ?? "documents"}</strong> submissions
        will have no approval workflow until another version is activated. Workflows already running are not affected.
      </>
    ),
    confirmLabel: "Deactivate",
    confirmClassName: "border-[var(--gold-foreground)] bg-[var(--gold-foreground)] text-white",
  },
  delete: {
    icon: Trash2,
    iconClassName: "bg-[var(--rust-tint)] text-[var(--rust)]",
    title: "Delete this template version?",
    description: (t) => (
      <>
        Version {t.versionNumber} of <strong className="font-semibold text-foreground">{t.name}</strong> and its stages
        will be deleted. This can&apos;t be undone.
      </>
    ),
    confirmLabel: "Delete",
    confirmClassName: "border-[var(--rust)] bg-[var(--rust)] text-white",
  },
  version: {
    icon: CopyPlus,
    iconClassName: "bg-primary/10 text-primary",
    title: "Create a new version?",
    description: (t) => (
      <>
        Copies version {t.versionNumber} - details and every stage - into a new draft you can edit. This version stays
        exactly as it is until the new one is activated.
      </>
    ),
    confirmLabel: "Create Draft",
    confirmClassName: "border-primary bg-primary text-primary-foreground",
  },
}

interface TemplateStatusActionsProps {
  template: WorkflowTemplateDto
  busy: boolean
  onActivate: () => void
  onDeactivate: () => void
  onDelete: () => void
  onCreateVersion: () => void
}

/**
 * The template page's header actions, each behind a confirmation and each
 * shown only when both the template's status and the caller's permissions
 * allow it: Activate (draft/inactive), Deactivate (active), Create new
 * version (not a draft), Delete (draft/inactive that never ran a workflow).
 */
export function TemplateStatusActions({ template, busy, onActivate, onDeactivate, onDelete, onCreateVersion }: TemplateStatusActionsProps) {
  const { hasPermission } = usePermissions()
  const [dialog, setDialog] = useState<TemplateAction | null>(null)

  const canToggle = hasPermission(PermissionCodes.WA_TEMPLATES_TOGGLE_ACTIVE)
  const canUpdate = hasPermission(PermissionCodes.WA_TEMPLATES_UPDATE)
  const canDelete = hasPermission(PermissionCodes.WA_TEMPLATES_DELETE)

  const showActivate = canToggle && template.status !== "active"
  const showDeactivate = canToggle && template.status === "active"
  const showVersion = canUpdate && template.status !== "draft"
  const showDelete = canDelete && template.status !== "active" && !template.hasInstances

  function confirm(action: TemplateAction) {
    if (action === "activate") onActivate()
    else if (action === "deactivate") onDeactivate()
    else if (action === "delete") onDelete()
    else onCreateVersion()
  }

  const config = dialog ? ACTION_CONFIG[dialog] : null
  const Icon = config?.icon

  return (
    <div className="flex flex-wrap items-center gap-2.5">
      {busy && <Loader2 size={16} className="animate-spin text-muted-foreground" aria-label="Working" />}
      {showVersion && (
        <ActionButton onClick={() => setDialog("version")} disabled={busy} className="border-[var(--border-strong)] bg-card text-foreground">
          <CopyPlus size={15} /> Create New Version
        </ActionButton>
      )}
      {showActivate && (
        <ActionButton
          onClick={() => setDialog("activate")}
          disabled={busy || template.stages.length === 0}
          title={template.stages.length === 0 ? "Add at least one stage first" : undefined}
          className="border-primary bg-primary text-primary-foreground"
        >
          <Power size={15} /> Activate
        </ActionButton>
      )}
      {showDeactivate && (
        <ActionButton onClick={() => setDialog("deactivate")} disabled={busy} className="border-[var(--gold-foreground)] bg-card text-[var(--gold-foreground)]">
          <PowerOff size={15} /> Deactivate
        </ActionButton>
      )}
      {showDelete && (
        <ActionButton onClick={() => setDialog("delete")} disabled={busy} className="border-[var(--rust)] bg-card text-[var(--rust)]">
          <Trash2 size={15} /> Delete
        </ActionButton>
      )}

      {dialog && config && Icon && (
        <AlertDialog open onOpenChange={(open) => !open && setDialog(null)}>
          <AlertDialogPortal>
            <AlertDialogPopup className="sm:max-w-lg">
              <span className={cn("mx-auto mb-4 flex size-11 items-center justify-center rounded-md", config.iconClassName)}>
                <Icon size={20} aria-hidden="true" />
              </span>
              <AlertDialogTitle>{config.title}</AlertDialogTitle>
              <AlertDialogDescription>{config.description(template)}</AlertDialogDescription>
              <div className="flex items-center justify-center gap-2.5">
                <AlertDialogClose className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center">
                  Cancel
                </AlertDialogClose>
                <AlertDialogClose
                  onClick={() => confirm(dialog)}
                  className={cn("border h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center", config.confirmClassName)}
                >
                  {config.confirmLabel}
                </AlertDialogClose>
              </div>
            </AlertDialogPopup>
          </AlertDialogPortal>
        </AlertDialog>
      )}
    </div>
  )
}

function ActionButton({
  onClick,
  disabled,
  className,
  title,
  children,
}: {
  onClick: () => void
  disabled: boolean
  className: string
  title?: string
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={cn(
        "flex items-center gap-1.5 border disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center",
        className
      )}
    >
      {children}
    </button>
  )
}
