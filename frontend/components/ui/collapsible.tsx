"use client"

import { Collapsible as CollapsiblePrimitive } from "@base-ui/react/collapsible"

/**
 * Expand/collapse section, used to group the Role Permission Configuration
 * screen's permission areas. Thin wrapper around `@base-ui/react/collapsible`
 * (already a project dependency via `components/ui/button.tsx`'s base-ui
 * usage) — no new package needed. Mirrors shadcn's own base-ui collapsible
 * registry item, so `bunx shadcn@latest add collapsible` produces the same
 * shape if you'd rather generate it than hand-roll it.
 */
function Collapsible({ ...props }: CollapsiblePrimitive.Root.Props) {
  return <CollapsiblePrimitive.Root data-slot="collapsible" {...props} />
}

function CollapsibleTrigger({ ...props }: CollapsiblePrimitive.Trigger.Props) {
  return (
    <CollapsiblePrimitive.Trigger data-slot="collapsible-trigger" {...props} />
  )
}

function CollapsibleContent({ ...props }: CollapsiblePrimitive.Panel.Props) {
  return (
    <CollapsiblePrimitive.Panel data-slot="collapsible-content" {...props} />
  )
}

export { Collapsible, CollapsibleTrigger, CollapsibleContent }
