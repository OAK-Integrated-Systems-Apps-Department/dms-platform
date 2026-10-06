import type { DashboardFormat } from "@/lib/dashboard/api"

/** How each kind of file is named and coloured on the dashboard - the colours of the apps that open them. */
export const FORMATS: Record<
  DashboardFormat,
  { label: string; color: string }
> = {
  pdf: { label: "PDF", color: "#d13438" },
  word: { label: "Word", color: "#2b579a" },
  sheet: { label: "Spreadsheets", color: "#217346" },
  slides: { label: "Presentations", color: "#c43e1c" },
  image: { label: "Images", color: "#8764b8" },
  archive: { label: "ZIP archives", color: "#b98a3e" },
  text: { label: "Text & email", color: "#4f7a8a" },
  other: { label: "Other", color: "#8b968f" },
}

/** A colour mixed toward black (`amount` 0-1) - the shaded sides of 3D shapes. */
export function shade(color: string, amount: number): string {
  return `color-mix(in oklab, ${color} ${Math.round((1 - amount) * 100)}%, black)`
}

/** A colour mixed toward white (`amount` 0-1) - the lit tops of 3D shapes. */
export function tint(color: string, amount: number): string {
  return `color-mix(in oklab, ${color} ${Math.round((1 - amount) * 100)}%, white)`
}
