"use client"

import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Check, Loader2, Pin, PinOff, Search } from "lucide-react"
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
import { useCurrentUser } from "@/lib/auth/use-current-user"
import {
  EVERYONE,
  addMembers,
  audienceProblem,
  clearMembers,
  summarizeAudience,
  switchMode,
  toInput,
  toggleMember,
  type AudienceDraft,
} from "@/lib/pins/audience"
import {
  fetchPinAudience,
  searchPinCandidates,
  type PinAudienceMode,
  type PinMemberDto,
} from "@/lib/pins/api"
import { usePins, type PinTarget } from "@/lib/pins/use-pins"
import { cn } from "@/lib/utils"
import { usePinAudienceStore } from "@/stores/pin-audience-store"

/** How many users the list shows at once; the search finds the rest. Matches the server's cap. */
const LIST_SIZE = 50

/**
 * The two choices. Both are about the users ticked in the list:
 * - Allow: only the ticked users see the pin (mode USERS).
 * - Don't allow: everyone sees it except the ticked users (mode ALL - a rule, so people who join later
 *   see it too). With nobody ticked, that is simply everyone.
 */
const MODES: { value: PinAudienceMode; label: string; hint: string }[] = [
  {
    value: "USERS",
    label: "Allow",
    hint: "Only the users you tick can see this pin. Everyone else can't.",
  },
  {
    value: "ALL",
    label: "Don't allow",
    hint: "The users you tick can't see this pin. Everyone else can.",
  },
]

/**
 * The one audience sheet, mounted once in the dashboard shell and opened by anything that pins
 * (see `usePinAudienceStore`). Choosing who sees a pin is part of pinning: there is no pin that is
 * only for yourself.
 *
 * Switching between Allow and Don't allow clears the ticks, because the two lists mean opposite
 * things and carrying one over would show the pin to exactly the wrong people. A pin never grants
 * access to the document: people who can't open it simply don't see the pin.
 */
export function PinAudienceSheet() {
  const target = usePinAudienceStore((state) => state.target)
  const close = usePinAudienceStore((state) => state.close)

  return (
    <Sheet open={target !== null} onOpenChange={(open) => !open && close()}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[520px]"
      >
        {target && (
          <AudienceForm
            key={target.documentId}
            target={target}
            onDone={close}
          />
        )}
      </SheetContent>
    </Sheet>
  )
}

/**
 * Editing starts from the audience already chosen, which has to be fetched; a new pin starts as
 * "Don't allow" with nobody ticked, meaning everyone. The editor below is only mounted once its
 * starting point is known, so it can hold the draft in plain state.
 */
function AudienceForm({
  target,
  onDone,
}: {
  target: PinTarget
  onDone: () => void
}) {
  const { isMine } = usePins()
  const editing = isMine(target.documentId)
  const current = useQuery({
    queryKey: ["pin-audience", target.documentId],
    queryFn: () => fetchPinAudience(target.documentId),
    enabled: editing,
    staleTime: 0,
  })

  if (editing && current.isPending) {
    return (
      <>
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2">
            <Pin size={18} className="text-primary" />
            Who can see this pin
          </SheetTitle>
        </SheetHeader>
        <p className="flex items-center gap-2 p-5 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" /> Loading…
        </p>
      </>
    )
  }

  return (
    <AudienceEditor
      target={target}
      editing={editing}
      initial={
        editing && current.data
          ? { mode: current.data.mode, members: current.data.members }
          : EVERYONE
      }
      onDone={onDone}
    />
  )
}

function AudienceEditor({
  target,
  editing,
  initial,
  onDone,
}: {
  target: PinTarget
  editing: boolean
  initial: AudienceDraft
  onDone: () => void
}) {
  const { pin, unpin, canUnpin, atLimit, limit, isSaving } = usePins()
  const { data: me } = useCurrentUser()
  // You always see your own pin, so you are never listed or counted as someone to allow or refuse.
  const [draft, setDraft] = useState<AudienceDraft>(() => ({
    ...initial,
    members: initial.members.filter((m) => m.userId !== me?.userId),
  }))
  const [error, setError] = useState<string | null>(null)

  const problem = audienceProblem(draft)
  // A new pin at the limit is refused by the server; say so before they build an audience for it.
  const blockedByLimit = !editing && atLimit

  async function save() {
    setError(null)
    try {
      await pin(target, toInput(draft))
      toast.success(editing ? "Pin updated." : "Pinned.")
      onDone()
    } catch (e) {
      setError(friendlyMessage(e, "Could not pin the document."))
    }
  }

  async function remove() {
    setError(null)
    try {
      await unpin(target)
      toast.success("Unpinned.")
      onDone()
    } catch {
      // Reported by a toast from usePins; the sheet stays open.
    }
  }

  return (
    <>
      <SheetHeader className="border-b border-border">
        <SheetTitle className="flex items-center gap-2">
          <Pin size={18} className="text-primary" />
          Who can see this pin
        </SheetTitle>
        <SheetDescription>
          {/* One span, so the space after the bold title survives the description's flex layout. */}
          <span>
            Choose who sees <strong>{target.title}</strong>
            {
              " in their pinned documents. Pinning never gives anyone access to it: users who can't open the document won't see the pin. You always see your own pins."
            }
          </span>
        </SheetDescription>
      </SheetHeader>

      <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-5">
        {blockedByLimit && (
          <p className="rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">
            You&apos;ve pinned {limit} documents, the most allowed. Unpin one to
            pin another.
          </p>
        )}

        <div
          role="radiogroup"
          aria-label="Allow or don't allow the users you tick"
          className="grid grid-cols-2 gap-2"
        >
          {MODES.map((option) => {
            const selected = draft.mode === option.value
            return (
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => setDraft((d) => switchMode(d, option.value))}
                className={cn(
                  "flex flex-col gap-0.5 rounded-lg border p-3 text-left transition-colors",
                  selected
                    ? "border-primary bg-primary/5 ring-1 ring-primary"
                    : "border-border hover:bg-muted/40"
                )}
              >
                <span className="text-[13.5px] font-semibold">
                  {option.label}
                </span>
                <span className="text-[12px] leading-snug text-muted-foreground">
                  {option.hint}
                </span>
              </button>
            )
          })}
        </div>

        <UserList
          mode={draft.mode}
          selected={draft.members}
          meId={me?.userId}
          onToggle={(person) => setDraft((d) => toggleMember(d, person))}
          onSelectAll={(people) => setDraft((d) => addMembers(d, people))}
          onClear={() => setDraft((d) => clearMembers(d))}
        />

        <p
          className={cn(
            "rounded-lg border px-3 py-2 text-[12.5px]",
            problem
              ? "border-destructive/30 bg-destructive/5 text-destructive"
              : "border-border bg-muted/40 text-foreground"
          )}
          aria-live="polite"
        >
          {problem ?? summarizeAudience(draft)}
        </p>

        {error && (
          <p role="alert" className="text-[12.5px] text-destructive">
            {error}
          </p>
        )}
      </div>

      <SheetFooter className="flex-row justify-end gap-2 border-t border-border">
        {editing && canUnpin && (
          <Button
            variant="outline"
            className="mr-auto text-destructive"
            onClick={() => void remove()}
            disabled={isSaving}
          >
            <PinOff size={15} /> Unpin
          </Button>
        )}
        <Button variant="outline" onClick={onDone} disabled={isSaving}>
          Cancel
        </Button>
        <Button
          onClick={() => void save()}
          disabled={isSaving || problem !== null || blockedByLimit}
        >
          {isSaving ? "Saving…" : editing ? "Save" : "Pin"}
        </Button>
      </SheetFooter>
    </>
  )
}

/**
 * The users of the caller's organisation, each with a tick box. Ticked users are what "Allow" and
 * "Don't allow" act on. Search narrows the list; "Select all" ticks everyone currently listed and
 * "Clear" unticks everyone. Users already ticked stay on top while nothing is being searched, so the
 * selection is always visible.
 */
function UserList({
  mode,
  selected,
  meId,
  onToggle,
  onSelectAll,
  onClear,
}: {
  mode: PinAudienceMode
  selected: PinMemberDto[]
  meId: string | undefined
  onToggle: (person: PinMemberDto) => void
  onSelectAll: (people: PinMemberDto[]) => void
  onClear: () => void
}) {
  const [q, setQ] = useState("")
  const [term, setTerm] = useState("")

  useEffect(() => {
    const timer = setTimeout(() => setTerm(q.trim()), 250)
    return () => clearTimeout(timer)
  }, [q])

  const results = useQuery({
    queryKey: ["pin-candidates", term, LIST_SIZE],
    queryFn: () => searchPinCandidates(term, LIST_SIZE),
    staleTime: 30_000,
  })

  const found = (results.data ?? []).filter((p) => p.userId !== meId)
  const ticked = new Set(selected.map((p) => p.userId))
  // With no search, ticked users come first even if they are beyond the first page of results.
  const rows = term
    ? found
    : [...selected, ...found.filter((p) => !ticked.has(p.userId))]
  const truncated = (results.data?.length ?? 0) >= LIST_SIZE

  return (
    <section className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-[12.5px] font-semibold">
          Users{" "}
          <span className="font-normal text-muted-foreground">
            · {selected.length} ticked
          </span>
        </h3>
        <div className="flex gap-1">
          <Button
            variant="outline"
            size="sm"
            onClick={() => onSelectAll(rows)}
            disabled={rows.length === 0}
          >
            Select all
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={onClear}
            disabled={selected.length === 0}
          >
            Clear
          </Button>
        </div>
      </div>

      <label className="flex h-9 items-center gap-2 rounded-lg border border-border bg-card px-3 focus-within:border-ring">
        <Search size={15} className="text-muted-foreground" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search users by name or email…"
          aria-label="Search users"
          className="h-full flex-1 bg-transparent text-[13px] outline-none"
        />
      </label>

      <ul
        aria-label={
          mode === "USERS"
            ? "Users allowed to see this pin"
            : "Users not allowed to see this pin"
        }
        className="max-h-[280px] overflow-y-auto rounded-lg border border-border"
      >
        {results.isPending ? (
          <li className="px-3 py-3 text-[12.5px] text-muted-foreground">
            Loading users…
          </li>
        ) : results.isError ? (
          <li className="px-3 py-3 text-[12.5px] text-destructive">
            Couldn&apos;t load users.
          </li>
        ) : rows.length === 0 ? (
          <li className="px-3 py-3 text-[12.5px] text-muted-foreground">
            {term
              ? "No users match."
              : "There are no other users in your organisation yet."}
          </li>
        ) : (
          rows.map((person) => {
            const on = ticked.has(person.userId)
            return (
              <li
                key={person.userId}
                className="border-b border-border last:border-b-0"
              >
                <button
                  type="button"
                  role="checkbox"
                  aria-checked={on}
                  onClick={() => onToggle(person)}
                  className={cn(
                    "flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-muted/50",
                    on && "bg-primary/5"
                  )}
                >
                  <span
                    className={cn(
                      "flex size-[18px] shrink-0 items-center justify-center rounded-[5px] border transition-colors",
                      on
                        ? "border-primary bg-primary text-primary-foreground"
                        : "border-[var(--border-strong)] bg-card"
                    )}
                  >
                    {on && <Check size={12} strokeWidth={3} />}
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate text-[13px] font-medium">
                      {person.displayName}
                    </span>
                    <span className="block truncate text-[11.5px] text-muted-foreground">
                      {person.email}
                    </span>
                  </span>
                </button>
              </li>
            )
          })
        )}
      </ul>
      {truncated && !term && (
        <p className="text-[11.5px] text-muted-foreground">
          Showing the first {LIST_SIZE} users. Search to find others.
        </p>
      )}
    </section>
  )
}
