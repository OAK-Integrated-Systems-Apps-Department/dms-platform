// AUTO-GENERATED — do not edit by hand.
// Source of truth: config/tooltip-content.yaml
// Regenerate with: bun run gen:tooltips

export const tooltipContent = {
  "permissions.actions.delete": "Delete",
  "permissions.actions.edit": "Edit",
  "permissions.actions.view": "View",
  "roles.actions.activate": "Activate",
  "roles.actions.deactivate": "Deactivate",
  "roles.actions.delete": "Delete",
  "roles.actions.edit": "Edit",
  "roles.actions.view": "View",
  "users.actions.activate": "Activate",
  "users.actions.deactivate": "Deactivate",
  "users.actions.edit": "Edit",
  "users.actions.resetPassword": "Reset password",
  "users.actions.view": "View",
  "users.pending.deactivateDisabled": "This user hasn't set up their account yet",
  "users.pending.resetPasswordDisabled": "This user hasn't set up their account yet"
} as const

export type TooltipKey = keyof typeof tooltipContent
