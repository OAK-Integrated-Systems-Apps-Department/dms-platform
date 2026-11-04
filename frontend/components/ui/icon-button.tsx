"use client"

import type { ButtonHTMLAttributes, ReactNode } from "react"

import { cn } from "@/lib/utils"

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  active?: boolean
  children: ReactNode
}

/** Circular icon button used for header controls (help, pin, notifications). */
export function IconButton({
  active,
  className,
  children,
  ...props
}: IconButtonProps) {
  return (
    <button
      type="button"
      className={cn(
        "relative flex size-[38px] items-center justify-center rounded-btn border border-border transition-colors",
        active
          ? "bg-primary/10 text-primary"
          : "bg-card text-muted-foreground hover:bg-muted",
        className
      )}
      {...props}
    >
      {children}
    </button>
  )
}
