import { tooltipContent, type TooltipKey } from "./tooltip-content.generated"

export type { TooltipKey }

/**
 * Look up tooltip copy by key, e.g. `getTooltip("users.actions.view")`.
 *
 * All tooltip text lives in one place: config/tooltip-content.yaml. Edit
 * that file, then run `bun run gen:tooltips` (or just `bun run dev` /
 * `bun run build`, which do it for you) to regenerate the typed keys this
 * reads from — an unknown key is a TypeScript error, not a runtime surprise.
 */
export function getTooltip(key: TooltipKey): string {
  return tooltipContent[key]
}
