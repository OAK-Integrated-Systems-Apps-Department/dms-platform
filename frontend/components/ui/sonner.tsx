"use client"

import { useTheme } from "next-themes"
import { Toaster as Sonner, type ToasterProps } from "sonner"

/**
 * shadcn's standard Sonner wrapper, themed off next-themes (see
 * components/theme-provider.tsx) and mapped onto Ledger's own CSS
 * variables (app/globals.css) rather than sonner's defaults, so toasts
 * pick up light/dark automatically and match the rest of the app's
 * cards instead of looking like an unstyled overlay.
 */
const Toaster = ({ ...props }: ToasterProps) => {
  const { theme = "system" } = useTheme()

  return (
    <Sonner
      theme={theme as ToasterProps["theme"]}
      className="toaster group"
      richColors
      closeButton
      toastOptions={{
        classNames: {
          // The same look as the app's other secondary buttons (white, bordered, green on hover), instead of
          // sonner's black pill, which clashes with the red error and green success toasts.
          actionButton:
            "!h-8 !rounded-btn !border !border-[var(--border-strong)] !bg-card !px-3 !text-[12.5px] !font-semibold !text-foreground hover:!border-primary hover:!bg-muted hover:!text-primary",
        },
      }}
      style={
        {
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
        } as React.CSSProperties
      }
      {...props}
    />
  )
}

export { Toaster }
