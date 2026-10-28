"use client"

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { WATERMARK_PLACEHOLDERS } from "@/lib/watermarks/constants"

interface PlaceholderChipsProps {
  /** Appends the token to the text field. Disabled while a template is selected. */
  onInsert: (token: string) => void
  disabled?: boolean
}

/**
 * The six placeholders, as buttons that type themselves into the text box.
 *
 * Buttons rather than a documented list because the tokens are exact: a
 * user typing `{userName}` or `{user_email}` gets a 400 naming the typo,
 * which is a worse first experience than clicking a chip that cannot be
 * mistyped. Each carries its own one-line explanation on hover and focus,
 * so what `{watermarkId}` actually puts on the page is discoverable without
 * leaving the dialog.
 */
export function PlaceholderChips({ onInsert, disabled = false }: PlaceholderChipsProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-[11.5px] text-muted-foreground">
        Insert a placeholder — it is filled in when the copy is made:
      </span>
      <div className="flex flex-wrap gap-1.5">
        {WATERMARK_PLACEHOLDERS.map(({ token, hint }) => (
          <Tooltip key={token}>
            <TooltipTrigger
              render={(props) => (
                <button
                  {...props}
                  type="button"
                  disabled={disabled}
                  onClick={() => onInsert(token)}
                  aria-label={`Insert ${token} — ${hint}`}
                  className="rounded-btn border border-border bg-muted/40 px-2 py-1 font-mono text-[11.5px] text-foreground transition-colors hover:border-[var(--border-strong)] hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {token}
                </button>
              )}
            />
            <TooltipContent>{hint}</TooltipContent>
          </Tooltip>
        ))}
      </div>
    </div>
  )
}
