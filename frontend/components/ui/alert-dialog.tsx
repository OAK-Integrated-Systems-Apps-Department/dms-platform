"use client"

import { AlertDialog as AlertDialogPrimitive } from "@base-ui/react/alert-dialog"

import { cn } from "@/lib/utils"

/**
 * Confirmation dialog for actions that change state and deserve a pause —
 * deactivating a user, deleting something, etc. Thin themed wrapper around
 * `@base-ui/react/alert-dialog` (same dependency `components/ui/button.tsx`
 * and `components/ui/collapsible.tsx` already pull from — no new package).
 *
 * `AlertDialog` (not the plain `Dialog` primitive) specifically because it
 * ignores backdrop clicks and forces an explicit Cancel/Confirm choice,
 * which is the right behavior for a destructive or account-affecting
 * action like Activate/Deactivate User.
 */
function AlertDialog({ ...props }: AlertDialogPrimitive.Root.Props) {
  return <AlertDialogPrimitive.Root data-slot="alert-dialog" {...props} />
}

function AlertDialogTrigger({ ...props }: AlertDialogPrimitive.Trigger.Props) {
  return (
    <AlertDialogPrimitive.Trigger data-slot="alert-dialog-trigger" {...props} />
  )
}

function AlertDialogPortal({
  children,
  ...props
}: AlertDialogPrimitive.Portal.Props) {
  return (
    <AlertDialogPrimitive.Portal data-slot="alert-dialog-portal" {...props}>
      <AlertDialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/40" />
      {children}
    </AlertDialogPrimitive.Portal>
  )
}

function AlertDialogPopup({
  className,
  ...props
}: AlertDialogPrimitive.Popup.Props) {
  return (
    <AlertDialogPrimitive.Popup
      data-slot="alert-dialog-popup"
      className={cn(
        "fixed top-1/2 left-1/2 z-50 w-full max-w-[380px] -translate-x-1/2 -translate-y-1/2 rounded-[10px] border border-border bg-card px-7 py-7 text-center shadow-[var(--ledger-shadow)]",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogTitle({
  className,
  ...props
}: AlertDialogPrimitive.Title.Props) {
  return (
    <AlertDialogPrimitive.Title
      data-slot="alert-dialog-title"
      className={cn(
        "mb-2 font-heading text-[18px] font-bold text-foreground",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogDescription({
  className,
  ...props
}: AlertDialogPrimitive.Description.Props) {
  return (
    <AlertDialogPrimitive.Description
      data-slot="alert-dialog-description"
      className={cn(
        "mb-[22px] text-[15px] leading-[1.6] text-muted-foreground",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogClose({ ...props }: AlertDialogPrimitive.Close.Props) {
  return (
    <AlertDialogPrimitive.Close data-slot="alert-dialog-close" {...props} />
  )
}

export {
  AlertDialog,
  AlertDialogTrigger,
  AlertDialogPortal,
  AlertDialogPopup,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogClose,
}
