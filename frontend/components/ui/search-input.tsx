"use client"

import { Search, X } from "lucide-react"

import { cn } from "@/lib/utils"

interface SearchInputProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  /** Wrapper classes — controls width/flex behavior (e.g. `min-w-[220px] flex-1`). */
  className?: string
  /** Override the input element's own classes (size, radius, padding, colors). */
  inputClassName?: string
  /** Show the leading search glyph. Defaults to true. */
  showIcon?: boolean
  "aria-label"?: string
  /** Anything else for the input itself - keyboard handlers, combobox ARIA (the top bar's search). */
  inputProps?: Omit<
    React.InputHTMLAttributes<HTMLInputElement>,
    "value" | "onChange" | "placeholder" | "className" | "type"
  >
}

/**
 * Text input for search/filter bars: leading search icon, and a trailing
 * clear (×) button that appears once there's a value and resets it back to
 * `""` in one click — previously every search bar in the app had no way to
 * clear a query except selecting the text and deleting it by hand.
 */
export function SearchInput({
  value,
  onChange,
  placeholder,
  className,
  inputClassName,
  showIcon = true,
  "aria-label": ariaLabel,
  inputProps,
}: SearchInputProps) {
  return (
    <div className={cn("relative", className)}>
      {showIcon && (
        <Search
          size={16}
          className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--ink-faint)]"
        />
      )}
      <input
        {...inputProps}
        type="text"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        aria-label={ariaLabel ?? placeholder}
        className={cn(
          "h-[38px] w-full min-w-0 rounded-btn border border-border bg-muted text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card",
          showIcon ? "pl-9" : "pl-3",
          value ? "pr-9" : "pr-3",
          inputClassName
        )}
      />
      {value && (
        <button
          type="button"
          onClick={() => onChange("")}
          aria-label="Clear search"
          className="absolute top-1/2 right-2.5 -translate-y-1/2 flex size-[18px] items-center justify-center rounded-full text-[var(--ink-faint)] transition-colors hover:bg-border/70 hover:text-foreground"
        >
          <X size={13} />
        </button>
      )}
    </div>
  )
}