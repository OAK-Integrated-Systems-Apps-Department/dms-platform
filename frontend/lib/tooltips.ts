import tooltipYaml from "@/config/tooltip-content.yaml"

interface TooltipNode {
  [key: string]: TooltipNode | string
}

const tooltipContent = tooltipYaml as Record<string, TooltipNode | string>

export function getTooltip(key: string): string {
  const parts = key.split(".")
  let node: TooltipNode | string = tooltipContent

  for (const part of parts) {
    if (typeof node !== "object" || node === null || !(part in node)) {
      throw new Error(`getTooltip: unknown tooltip key "${key}" ...`)
    }
    node = node[part]
  }

  if (typeof node !== "string") {
    throw new Error(`getTooltip: tooltip key "${key}" does not resolve to a string`)
  }

  return node
}