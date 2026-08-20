import type { ReactNode } from "react"

import { cn } from "@/lib/utils"

interface DropdownProps {
  children: ReactNode
  title?: string
  width?: number
  noPad?: boolean
  className?: string
}

/** Floating panel used by the pin, notifications, account and settings menus. */
export function Dropdown({ children, title, width = 260, noPad, className }: DropdownProps) {
  return (
    <div
      style={{ width }}
      className={cn(
        "absolute top-[46px] right-0 z-40 overflow-hidden rounded-xl border border-border bg-popover shadow-[var(--ledger-shadow)]",
        noPad ? "" : "py-1.5",
        className
      )}
    >
      {title && (
        <div className="border-b border-border px-3.5 pt-2.5 pb-2 text-xs font-semibold tracking-wide text-muted-foreground">
          {title}
        </div>
      )}
      {children}
    </div>
  )
}

interface MenuRowProps {
  icon: React.ComponentType<{ size?: number }>
  label: string
  danger?: boolean
  onClick?: () => void
}

export function MenuRow({ icon: Icon, label, danger, onClick }: MenuRowProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-2.5 px-3.5 py-[9px] text-left text-[13px] hover:bg-muted",
        danger ? "text-destructive" : "text-foreground"
      )}
    >
      <Icon size={15} />
      {label}
    </button>
  )
}
