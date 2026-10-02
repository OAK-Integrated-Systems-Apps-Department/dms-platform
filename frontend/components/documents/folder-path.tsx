"use client"

import { Fragment } from "react"
import {
  ArrowUp,
  ChevronRight,
  Folder,
  FolderOpen,
  House,
  Lock,
} from "lucide-react"

import { ItemMenu } from "@/components/documents/browser-items"
import type { FolderNode } from "@/lib/folders/api"
import { cn } from "@/lib/utils"

/** Paths longer than this fold their middle folders into a "…" menu. */
const MAX_VISIBLE = 3

const SEGMENT =
  "inline-flex max-w-[220px] items-center gap-1.5 rounded-md px-2 py-1 text-[14px] transition-colors"

/**
 * Where you are in My Documents: an "up one level" button, then
 * My Documents › Finance › Invoices, the current folder last and bold.
 * Long paths keep the first folder and the last two, with the rest in a
 * "…" menu. `summary` (e.g. "2 folders · 5 documents") sits on the right.
 */
export function FolderPath({
  trail,
  onOpen,
  summary,
}: {
  /** Folders from the top level down to the current one; empty at the top level. */
  trail: FolderNode[]
  onOpen: (folderId: string | null) => void
  summary?: string
}) {
  const current = trail.at(-1)
  const parent = trail.at(-2)
  const hidden =
    trail.length > MAX_VISIBLE ? trail.slice(1, trail.length - 2) : []
  const visible = hidden.length > 0 ? [trail[0], ...trail.slice(-2)] : trail

  return (
    <div className="flex min-w-0 flex-wrap items-center gap-2">
      <button
        type="button"
        title="Up one level"
        aria-label="Up one level"
        disabled={!current}
        onClick={() => onOpen(parent?.folderId ?? null)}
        className="flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-40"
      >
        <ArrowUp size={16} />
      </button>
      <span aria-hidden="true" className="h-5 w-px bg-border" />

      <nav aria-label="Folder path" className="min-w-0 flex-1">
        <ol className="flex flex-wrap items-center gap-0.5">
          <li>
            {current ? (
              <button
                type="button"
                onClick={() => onOpen(null)}
                className={cn(
                  SEGMENT,
                  "text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
              >
                <House size={15} className="shrink-0" />
                My Documents
              </button>
            ) : (
              <span
                aria-current="page"
                className={cn(SEGMENT, "font-semibold text-foreground")}
              >
                <House size={15} className="shrink-0 text-primary" />
                My Documents
              </span>
            )}
          </li>
          {visible.map((node, i) => (
            <Fragment key={node.folderId}>
              {i === 1 && hidden.length > 0 && (
                <>
                  <Separator />
                  <li>
                    <ItemMenu
                      label="the path"
                      buttonLabel={`Show ${hidden.length} more ${hidden.length === 1 ? "folder" : "folders"} in the path`}
                      actions={hidden.map((folder) => ({
                        label: folder.name,
                        icon: Folder,
                        onSelect: () => onOpen(folder.folderId),
                      }))}
                    />
                  </li>
                </>
              )}
              <Separator />
              <li className="min-w-0">
                {node === current ? (
                  <span
                    aria-current="page"
                    title={node.path}
                    className={cn(SEGMENT, "font-semibold text-foreground")}
                  >
                    <FolderOpen size={15} className="shrink-0 text-primary" />
                    <span className="truncate">{node.name}</span>
                    {node.restricted && (
                      <Lock
                        size={13}
                        className="shrink-0 text-muted-foreground"
                        aria-label="Restricted"
                      />
                    )}
                  </span>
                ) : (
                  <button
                    type="button"
                    title={node.path}
                    onClick={() => onOpen(node.folderId)}
                    className={cn(
                      SEGMENT,
                      "text-muted-foreground hover:bg-muted hover:text-foreground"
                    )}
                  >
                    <span className="truncate">{node.name}</span>
                  </button>
                )}
              </li>
            </Fragment>
          ))}
        </ol>
      </nav>

      {summary && (
        <span className="shrink-0 text-[12.5px] text-muted-foreground">
          {summary}
        </span>
      )}
    </div>
  )
}

function Separator() {
  return (
    <li aria-hidden="true" className="text-muted-foreground/60">
      <ChevronRight size={14} />
    </li>
  )
}
