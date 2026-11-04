"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useEffect, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Building2,
  CornerLeftUp,
  Loader2,
  Lock,
  Search,
  ShieldCheck,
  User,
  Users,
  X,
  type LucideIcon,
} from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { friendlyMessage } from "@/lib/api-error"
import { fetchDocumentAccess, saveDocumentAccess } from "@/lib/documents/api"
import {
  fetchFolderAccess,
  saveFolderAccess,
  searchAccessPrincipals,
  type FolderAccessEntryDto,
  type FolderAccessMode,
} from "@/lib/folders/api"
import { FOLDERS_KEY } from "@/lib/folders/use-folders"
import { cn } from "@/lib/utils"

const LEVELS: { value: FolderAccessEntryDto["level"]; label: string }[] = [
  { value: "view", label: "Can view" },
  { value: "edit", label: "Can edit" },
  { value: "manage", label: "Can manage" },
]

type ModeOption = {
  value: FolderAccessMode
  icon: LucideIcon
  title: string
  description: string
}

/** What gets its access managed: a folder (V53) or a single document (V54). */
export type AccessTarget = {
  kind: "folder" | "document"
  id: string
  name: string
}

/** The parts of a folder's or document's access the editor needs. */
interface AccessState {
  mode: FolderAccessMode
  effectiveMode: "everyone" | "restricted"
  inheritedFromName: string | null
  entries: FolderAccessEntryDto[]
  canManage: boolean
}

const DOCUMENT_MODES: ModeOption[] = [
  {
    value: "inherit",
    icon: CornerLeftUp,
    title: "Same as its folder",
    description:
      "Follows the folder it's filed in. At the top level it's open to everyone.",
  },
  {
    value: "restricted",
    icon: Lock,
    title: "Only specific people and roles",
    description:
      "Only they (and admins) can open it, whatever its folder allows - others won't see it.",
  },
]

const FOLDER_MODES: ModeOption[] = [
  {
    value: "inherit",
    icon: CornerLeftUp,
    title: "Same as the folder it's in",
    description:
      "Follows the parent folder. A top-level folder is open to everyone.",
  },
  {
    value: "everyone",
    icon: Building2,
    title: "Everyone in the organisation",
    description: "Anyone who can use My Documents can open it.",
  },
  {
    value: "restricted",
    icon: Lock,
    title: "Only specific people and roles",
    description: "Hidden from everyone else - they won't even see it exists.",
  },
]

/**
 * "Manage access" for a folder (V53: inherit, everyone, or restricted) or a
 * document (V54: follow its folder, or restricted to its own list), with
 * chosen users and roles at view / edit / manage. Read-only for people who
 * can see the access list but not change it. Admins always keep access.
 */
export function AccessSheet({
  target,
  open,
  onOpenChange,
}: {
  target: AccessTarget
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const access = useQuery<AccessState>({
    queryKey: [`${target.kind}-access`, target.id],
    queryFn: () =>
      target.kind === "folder"
        ? fetchFolderAccess(target.id)
        : fetchDocumentAccess(target.id),
    enabled: open,
  })

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[520px]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2">
            <ShieldCheck size={18} className="text-primary" />
            Manage access
          </SheetTitle>
          <SheetDescription>
            {target.kind === "folder" ? (
              <>
                Who can see and work in <strong>{target.name}</strong>, its
                documents and the subfolders that follow it.
              </>
            ) : (
              <>
                Who can open and work with <strong>{target.name}</strong>.
              </>
            )}
          </SheetDescription>
        </SheetHeader>
        {access.isPending ? (
          <p className="inline-flex items-center gap-2 p-5 text-[13px] text-muted-foreground">
            <Loader2 size={14} className="animate-spin" /> Loading…
          </p>
        ) : access.isError ? (
          <LoadFailed error={access.error} fallback="Could not load who has access." />
        ) : (
          <AccessEditor
            key={access.dataUpdatedAt}
            target={target}
            access={access.data}
            onDone={() => onOpenChange(false)}
          />
        )}
      </SheetContent>
    </Sheet>
  )
}

type Draft = Pick<
  FolderAccessEntryDto,
  "principalType" | "principalId" | "name" | "detail" | "level"
>

function AccessEditor({
  target,
  access,
  onDone,
}: {
  target: AccessTarget
  access: AccessState
  onDone: () => void
}) {
  const modes = target.kind === "folder" ? FOLDER_MODES : DOCUMENT_MODES
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<FolderAccessMode>(access.mode)
  // Starting to restrict an inheriting folder copies the list it inherits.
  const [entries, setEntries] = useState<Draft[]>(access.entries)
  const [saving, setSaving] = useState(false)
  const readOnly = !access.canManage
  const inheritedLabel =
    access.effectiveMode === "restricted"
      ? `Restricted - set on "${access.inheritedFromName ?? target.name}"`
      : "Open to everyone"

  async function save() {
    setSaving(true)
    try {
      const list =
        mode === "restricted"
          ? entries.map(({ principalType, principalId, level }) => ({
              principalType,
              principalId,
              level,
            }))
          : []
      if (target.kind === "folder") {
        await saveFolderAccess(target.id, mode, list)
      } else {
        await saveDocumentAccess(
          target.id,
          mode === "restricted" ? "restricted" : "inherit",
          list
        )
      }
      toast.success(`Access to "${target.name}" updated.`)
      void queryClient.invalidateQueries({ queryKey: FOLDERS_KEY })
      void queryClient.invalidateQueries({ queryKey: ["documents"] })
      void queryClient.invalidateQueries({ queryKey: ["folder-access"] })
      void queryClient.invalidateQueries({ queryKey: ["document-access"] })
      onDone()
    } catch (e) {
      toast.error(friendlyMessage(e, "Could not save who has access."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="flex flex-1 flex-col gap-5 overflow-y-auto p-5">
        <div
          role="radiogroup"
          aria-label={`Who can access this ${target.kind}`}
          className="flex flex-col gap-2"
        >
          {modes.map((option) => {
            const selected = mode === option.value
            return (
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={selected}
                disabled={readOnly}
                onClick={() => setMode(option.value)}
                className={cn(
                  "flex items-start gap-3 rounded-lg border p-3 text-left transition-colors disabled:cursor-default",
                  selected
                    ? "border-primary bg-primary/5 ring-1 ring-primary"
                    : "border-border hover:bg-muted/40 disabled:hover:bg-transparent"
                )}
              >
                <span
                  className={cn(
                    "flex size-8 shrink-0 items-center justify-center rounded-md",
                    selected
                      ? "bg-primary text-primary-foreground"
                      : "bg-muted text-muted-foreground"
                  )}
                >
                  <option.icon size={16} />
                </span>
                <span>
                  <span className="block text-[13.5px] font-semibold">
                    {option.title}
                  </span>
                  <span className="block text-[12.5px] text-muted-foreground">
                    {option.value === "inherit" && access.mode === "inherit"
                      ? `${option.description} Now: ${inheritedLabel}.`
                      : option.description}
                  </span>
                </span>
              </button>
            )
          })}
        </div>

        {mode === "restricted" && (
          <section aria-label="People and roles with access">
            <h3 className="mb-2 text-[13px] font-semibold">
              People and roles with access
            </h3>
            {!readOnly && (
              <PrincipalPicker
                taken={
                  new Set(
                    entries.map((e) => `${e.principalType}:${e.principalId}`)
                  )
                }
                onPick={(p) =>
                  setEntries([...entries, { ...p, level: "view" }])
                }
              />
            )}
            {entries.length === 0 ? (
              <p className="mt-3 rounded-lg border border-dashed border-border px-3.5 py-4 text-center text-[12.5px] text-muted-foreground">
                Nobody added yet. You&apos;ll be kept as a manager, and admins
                always have access.
              </p>
            ) : (
              <ul className="mt-3 divide-y divide-border rounded-lg border border-border">
                {entries.map((entry, i) => (
                  <li
                    key={`${entry.principalType}:${entry.principalId}`}
                    className="flex items-center gap-3 px-3 py-2.5"
                  >
                    <PrincipalAvatar type={entry.principalType} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium">
                        {entry.name}
                      </span>
                      {entry.detail && (
                        <span className="block truncate text-[11.5px] text-muted-foreground">
                          {entry.detail}
                        </span>
                      )}
                    </span>
                    <select
                      aria-label={`Access for ${entry.name}`}
                      value={entry.level}
                      disabled={readOnly}
                      onChange={(e) =>
                        setEntries(
                          entries.map((x, j) =>
                            j === i
                              ? {
                                  ...x,
                                  level: e.target.value as Draft["level"],
                                }
                              : x
                          )
                        )
                      }
                      className="h-8 rounded-btn border border-border bg-card px-2 text-[12.5px]"
                    >
                      {LEVELS.map((l) => (
                        <option key={l.value} value={l.value}>
                          {l.label}
                        </option>
                      ))}
                    </select>
                    {!readOnly && (
                      <button
                        type="button"
                        aria-label={`Remove ${entry.name}`}
                        onClick={() =>
                          setEntries(entries.filter((_, j) => j !== i))
                        }
                        className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
                      >
                        <X size={14} />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            <p className="mt-2 text-[11.5px] leading-[1.55] text-muted-foreground">
              View: open and download. Edit: also upload, move documents and
              create subfolders. Manage: also move, delete and change access.
              What people can do still depends on their role&apos;s permissions.
            </p>
          </section>
        )}
      </div>
      <SheetFooter className="flex-row justify-end gap-2 border-t border-border">
        {readOnly ? (
          <>
            <p className="mr-auto self-center text-[12px] text-muted-foreground">
              You can see this list but not change it.
            </p>
            <Button variant="outline" onClick={onDone}>
              Close
            </Button>
          </>
        ) : (
          <>
            <Button variant="outline" onClick={onDone} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={() => void save()} disabled={saving}>
              {saving && <Loader2 className="animate-spin" />}
              {saving ? "Saving…" : "Save"}
            </Button>
          </>
        )}
      </SheetFooter>
    </>
  )
}

function PrincipalAvatar({ type }: { type: "user" | "role" }) {
  return (
    <span
      aria-hidden="true"
      className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
    >
      {type === "user" ? <User size={15} /> : <Users size={15} />}
    </span>
  )
}

/** Search box listing matching users and roles; picking one adds it. */
function PrincipalPicker({
  taken,
  onPick,
}: {
  taken: Set<string>
  onPick: (principal: Omit<Draft, "level">) => void
}) {
  const [q, setQ] = useState("")
  const [term, setTerm] = useState("")
  const [focused, setFocused] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setTerm(q.trim()), 250)
    return () => clearTimeout(timer)
  }, [q])

  const results = useQuery({
    queryKey: ["access-principals", term],
    queryFn: () => searchAccessPrincipals(term),
    enabled: focused,
    staleTime: 30_000,
  })
  const options = (results.data ?? []).filter(
    (p) => !taken.has(`${p.principalType}:${p.principalId}`)
  )

  return (
    <div className="relative">
      <label className="flex h-9 items-center gap-2 rounded-lg border border-border bg-card px-3 focus-within:border-ring">
        <Search size={15} className="text-muted-foreground" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setTimeout(() => setFocused(false), 150)}
          placeholder="Add people or roles…"
          aria-label="Add people or roles"
          className="h-full flex-1 bg-transparent text-[13px] outline-none"
        />
      </label>
      {focused && (
        <ul className="absolute top-10 right-0 left-0 z-50 max-h-[260px] overflow-y-auto rounded-lg border border-border bg-popover py-1 shadow-[0_8px_24px_rgba(0,0,0,0.18)]">
          {results.isPending ? (
            <li className="px-3 py-2 text-[12.5px] text-muted-foreground">
              Searching…
            </li>
          ) : options.length === 0 ? (
            <li className="px-3 py-2 text-[12.5px] text-muted-foreground">
              No matching people or roles.
            </li>
          ) : (
            options.map((p) => (
              <li key={`${p.principalType}:${p.principalId}`}>
                <button
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => {
                    onPick(p)
                    setQ("")
                  }}
                  className="flex w-full items-center gap-2.5 px-3 py-2 text-left hover:bg-muted"
                >
                  <PrincipalAvatar type={p.principalType} />
                  <span className="min-w-0">
                    <span className="block truncate text-[13px] font-medium">
                      {p.name}
                    </span>
                    {p.detail && (
                      <span className="block truncate text-[11.5px] text-muted-foreground">
                        {p.detail}
                      </span>
                    )}
                  </span>
                </button>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  )
}
