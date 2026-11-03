"use client"

import { Maximize2, PanelTop } from "lucide-react"

import { cn } from "@/lib/utils"
import { type EditorLayout } from "@/lib/checkout/api"
import { useSaveUserPreferences, useUserPreferences } from "@/lib/checkout/use-checkout"

const OPTIONS: Array<{ value: EditorLayout; title: string; text: string; icon: typeof Maximize2 }> = [
  {
    value: "fullpage",
    title: "Full page",
    text: "The editor fills the whole window, with only a slim bar to go back or finish. Most room to write.",
    icon: Maximize2,
  },
  {
    value: "inline",
    title: "Inside the DMS page",
    text: "The editor opens under the usual menus and breadcrumb, so the rest of the DMS stays in reach.",
    icon: PanelTop,
  },
]

/**
 * My Profile: where "Edit online" opens the document editor. Saved per user; inside the DMS page until they choose
 * (the editor there has an "Open full screen" button for a one-off full-screen tab).
 */
export function EditorLayoutCard() {
  const { data } = useUserPreferences()
  const save = useSaveUserPreferences()
  const current: EditorLayout = data?.editorLayout ?? "inline"

  return (
    <section className="rounded-lg border border-border bg-card p-5 shadow-[var(--ledger-shadow)]">
      <h2 className="text-[15px] font-semibold">Document editor</h2>
      <p className="mt-1 mb-3.5 text-[12.5px] text-muted-foreground">Choose how the online editor opens for you.</p>
      <div role="radiogroup" aria-label="Where the document editor opens" className="grid gap-2.5 sm:grid-cols-2">
        {OPTIONS.map(({ value, title, text, icon: Icon }) => {
          const selected = current === value
          return (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={save.isPending}
              onClick={() => !selected && save.mutate({ editorLayout: value })}
              className={cn(
                "flex items-start gap-3 rounded-lg border p-3.5 text-left transition-colors disabled:opacity-60",
                selected ? "border-primary bg-primary/5" : "border-border hover:bg-muted"
              )}
            >
              <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <Icon size={16} />
              </span>
              <span className="min-w-0">
                <span className="block text-[13.5px] font-semibold text-foreground">{title}</span>
                <span className="block text-[12.5px] leading-[1.5] text-muted-foreground">{text}</span>
              </span>
            </button>
          )
        })}
      </div>
    </section>
  )
}
