"use client"

import { useEffect, useRef, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Lock } from "lucide-react"

import { Textarea } from "@/components/ui/textarea"
import { lookupPeople, type PersonOptionDto } from "@/lib/workflow/api"
import { cn } from "@/lib/utils"

import { MENTION_TOKEN } from "./mention-text"

export interface MentionRef {
  userId: string
  name: string
}

/** What the person types and sees: "@Nimal Perera", plus who each "@Name" points to. */
export interface MentionDraft {
  text: string
  mentions: MentionRef[]
}

/** Draft -> the body the backend stores: each "@Name" of a picked person becomes its token (A5). */
export function toWireBody({ text, mentions }: MentionDraft): string {
  let body = text
  for (const m of [...mentions].sort((a, b) => b.name.length - a.name.length)) {
    body = body.split(`@${m.name}`).join(`@[${m.name}](user:${m.userId})`)
  }
  return body
}

/** A stored body back to an editable draft. */
export function fromWireBody(body: string): MentionDraft {
  const mentions: MentionRef[] = []
  const text = body.replace(MENTION_TOKEN, (_all, name: string, userId: string) => {
    if (!mentions.some((m) => m.userId === userId)) mentions.push({ userId, name })
    return `@${name}`
  })
  return { text, mentions }
}

/** The "@query" being typed right before the caret, if any. */
function activeQuery(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret)
  const at = before.lastIndexOf("@")
  if (at < 0 || (at > 0 && !/\s/.test(before[at - 1]))) return null
  const query = before.slice(at + 1)
  if (query.length > 30 || /\n/.test(query) || /\s{2}/.test(query)) return null
  return { start: at, query }
}

interface MentionTextareaProps {
  id: string
  value: MentionDraft
  onChange: (value: MentionDraft) => void
  placeholder?: string
  rows?: number
  disabled?: boolean
  onSubmitShortcut?: () => void
}

/**
 * A comment box with @mentions: typing "@" and a few letters suggests
 * people of the tenant (people without document access are marked - they
 * still get the comment, decision 10); Enter or a click inserts "@Name".
 */
export function MentionTextarea({ id, value, onChange, placeholder, rows = 3, disabled, onSubmitShortcut }: MentionTextareaProps) {
  const ref = useRef<HTMLTextAreaElement>(null)
  const [query, setQuery] = useState<{ start: number; query: string } | null>(null)
  const [debounced, setDebounced] = useState("")
  const [highlight, setHighlight] = useState(0)

  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(query?.query ?? ""), 200)
    return () => window.clearTimeout(t)
  }, [query?.query])

  const people = useQuery({
    queryKey: ["people-lookup", debounced],
    queryFn: () => lookupPeople(debounced, 8),
    enabled: query !== null,
    staleTime: 30_000,
    retry: false,
  })
  const options: PersonOptionDto[] = query ? people.data ?? [] : []

  function update(text: string, caret: number) {
    // Drop mentions whose "@Name" was deleted from the text.
    const mentions = value.mentions.filter((m) => text.includes(`@${m.name}`))
    onChange({ text, mentions })
    setQuery(activeQuery(text, caret))
    setHighlight(0)
  }

  function pick(person: PersonOptionDto) {
    if (!query) return
    const name = person.displayName ?? person.username ?? "someone"
    const caret = ref.current?.selectionStart ?? value.text.length
    const text = `${value.text.slice(0, query.start)}@${name} ${value.text.slice(caret)}`
    const mentions = value.mentions.some((m) => m.userId === person.userId)
      ? value.mentions
      : [...value.mentions, { userId: person.userId, name }]
    onChange({ text, mentions })
    setQuery(null)
    const next = query.start + name.length + 2
    requestAnimationFrame(() => {
      ref.current?.focus()
      ref.current?.setSelectionRange(next, next)
    })
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (query && options.length > 0) {
      if (e.key === "ArrowDown") {
        e.preventDefault()
        setHighlight((h) => (h + 1) % options.length)
        return
      }
      if (e.key === "ArrowUp") {
        e.preventDefault()
        setHighlight((h) => (h - 1 + options.length) % options.length)
        return
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault()
        pick(options[highlight])
        return
      }
    }
    if (e.key === "Escape") setQuery(null)
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      onSubmitShortcut?.()
    }
  }

  return (
    <div className="relative">
      <Textarea
        ref={ref}
        id={id}
        rows={rows}
        maxLength={3500}
        disabled={disabled}
        placeholder={placeholder}
        value={value.text}
        onChange={(e) => update(e.target.value, e.target.selectionStart)}
        onKeyDown={onKeyDown}
        onBlur={() => window.setTimeout(() => setQuery(null), 150)}
        role="combobox"
        aria-expanded={query !== null && options.length > 0}
        aria-autocomplete="list"
      />
      {query && (options.length > 0 || people.isPending) && (
        <ul className="absolute right-0 left-0 z-20 mt-1 max-h-[220px] overflow-y-auto rounded-btn border border-border bg-popover shadow-[var(--ledger-shadow)]" role="listbox">
          {people.isPending && options.length === 0 && (
            <li className="px-3 py-2 text-[12.5px] text-muted-foreground">Searching…</li>
          )}
          {options.map((person, i) => (
            <li key={person.userId} role="option" aria-selected={i === highlight}>
              <button
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pick(person)}
                className={cn("flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-[13px]", i === highlight && "bg-muted")}
              >
                <span>
                  <span className="font-medium text-foreground">{person.displayName ?? person.username}</span>
                  {person.departmentName && <span className="ml-1.5 text-[12px] text-muted-foreground">{person.departmentName}</span>}
                </span>
                {person.hasDocumentAccess === false && (
                  <span className="flex items-center gap-1 text-[11px] text-[var(--gold-foreground)]" title="They'll get the comment but can't open the document">
                    <Lock size={10} /> No document access
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
