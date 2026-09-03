#!/usr/bin/env node
// Reads config/tooltip-content.yaml and generates lib/tooltip-content.generated.ts
// — a flat, typed object every page imports tooltip copy from (via lib/tooltips.ts)
// instead of hardcoding strings per-page.
//
// Run manually with `bun run gen:tooltips`. Also wired up to run
// automatically before `dev` and `build` (see package.json), so editing the
// YAML is always enough — no separate step to remember.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { parse } from "yaml"

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(__dirname, "..")
const SOURCE_PATH = path.join(root, "config/tooltip-content.yaml")
const OUTPUT_PATH = path.join(root, "lib/tooltip-content.generated.ts")

/** Flattens nested YAML sections into "a.b.c" -> "text" entries. */
function flatten(node, prefix = "", out = {}) {
  for (const [key, value] of Object.entries(node ?? {})) {
    const flatKey = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === "object" && !Array.isArray(value)) {
      flatten(value, flatKey, out)
    } else if (typeof value === "string") {
      out[flatKey] = value
    } else {
      throw new Error(
        `tooltip-content.yaml: "${flatKey}" must be a string, got ${typeof value}`
      )
    }
  }
  return out
}

function main() {
  const raw = readFileSync(SOURCE_PATH, "utf8")
  const parsed = parse(raw)
  const flat = flatten(parsed)

  const keys = Object.keys(flat).sort()
  if (keys.length === 0) {
    throw new Error("tooltip-content.yaml produced no tooltip entries")
  }

  const seenLower = new Map()
  for (const key of keys) {
    const lower = key.toLowerCase()
    if (seenLower.has(lower) && seenLower.get(lower) !== key) {
      throw new Error(`tooltip-content.yaml: keys differ only by case: "${seenLower.get(lower)}" / "${key}"`)
    }
    seenLower.set(lower, key)
  }

  const sortedEntries = {}
  for (const key of keys) sortedEntries[key] = flat[key]

  const contents = `// AUTO-GENERATED — do not edit by hand.
// Source of truth: config/tooltip-content.yaml
// Regenerate with: bun run gen:tooltips

export const tooltipContent = ${JSON.stringify(sortedEntries, null, 2)} as const

export type TooltipKey = keyof typeof tooltipContent
`

  mkdirSync(path.dirname(OUTPUT_PATH), { recursive: true })
  writeFileSync(OUTPUT_PATH, contents)

  console.log(
    `[gen:tooltips] wrote ${keys.length} ${keys.length === 1 ? "entry" : "entries"} to ${path.relative(root, OUTPUT_PATH)}`
  )
}

main()
