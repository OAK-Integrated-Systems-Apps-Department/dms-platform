import { cn } from "@/lib/utils"

import type { AssignedUserStatus } from "@/components/roles/types"

interface UserStatusToggleProps {
  status: AssignedUserStatus
  onToggle: () => void
}

/** The pill switch + label from the mockup's .status-toggle / .status-label. */
export function UserStatusToggle({ status, onToggle }: UserStatusToggleProps) {
  const isActive = status === "Active"
  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        role="switch"
        aria-checked={isActive}
        onClick={onToggle}
        className={cn(
          "relative h-[22px] w-10 shrink-0 rounded-full transition-colors",
          isActive ? "bg-primary" : "bg-[var(--border-strong)]"
        )}
      >
        <span
          className={cn(
            "absolute top-0.5 size-[18px] rounded-full bg-white shadow-sm transition-[left]",
            isActive ? "left-5" : "left-0.5"
          )}
        />
      </button>
      <span className="text-[12.5px] text-muted-foreground">{isActive ? "Active" : "Inactive"}</span>
    </div>
  )
}