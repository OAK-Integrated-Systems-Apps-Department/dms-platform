"use client"

import { useId, useRef, useState, type ClipboardEvent, type KeyboardEvent } from "react"
import { X } from "lucide-react"

import { cn } from "@/lib/utils"

// Commas and line breaks both end a tag: commas because that's what people
// type between items, line breaks because a list pasted from a spreadsheet
// column or another document arrives one-per-line.
const TAG_SEPARATOR = /[,\r\n]/

/** Splits typed/pasted text on commas and line breaks into trimmed, non-empty candidate tags, in order. */
export function splitTagText(text: string): string[] {
  return text
    .split(TAG_SEPARATOR)
    .map((piece) => piece.trim())
    .filter(Boolean)
}

/**
 * For text arriving through the input's own `onChange` (a mobile keyboard
 * that doesn't report `key === ","` on keydown, or autofill): everything up
 * to the LAST separator is finished and should become tags; whatever comes
 * after it is still being typed and stays in the input.
 */
export function extractCompletedTags(text: string): { completed: string[]; remainder: string } {
  let lastSeparator = -1
  for (let i = text.length - 1; i >= 0; i--) {
    if (TAG_SEPARATOR.test(text[i])) {
      lastSeparator = i
      break
    }
  }
  if (lastSeparator < 0) return { completed: [], remainder: text }
  return { completed: splitTagText(text.slice(0, lastSeparator)), remainder: text.slice(lastSeparator + 1).trimStart() }
}

export interface AddTagsOptions {
  /** Applied to every incoming tag before validation and duplicate checks. Defaults to trimming. */
  normalize?: (raw: string) => string
  /** Returns why a (normalized) tag can't be added, or `null` if it can. */
  validate?: (tag: string) => string | null
  /** Hard cap on the list's length — incoming tags past it are rejected, not silently dropped. */
  maxTags?: number
  /** Identity two tags are duplicates by. Defaults to `toLowerCase()`; label hints pass the server's per-code-point case fold. */
  dedupeKey?: (tag: string) => string
}

export interface AddTagsResult {
  /** The new list — the SAME array as `current` when nothing was added, so callers can skip a no-op `onChange`. */
  tags: string[]
  /** Normalized tags that were refused (invalid, or over `maxTags`) — handed back so the input can keep them for the user to fix. */
  rejected: string[]
  /** The first refusal's reason, for display — `null` when everything was accepted. */
  error: string | null
}

/**
 * Appends `incoming` to `current`, in order. Each tag is normalized, blanks
 * are skipped, and case-insensitive duplicates (of an existing tag or of an
 * earlier incoming one) are dropped silently — the list is ordered by
 * priority, so the copy that's already there keeps its place. Invalid tags
 * and tags past `maxTags` are refused and reported instead of dropped, since
 * silently losing something the user typed is worse than telling them.
 */
export function addTags(current: readonly string[], incoming: readonly string[], options: AddTagsOptions = {}): AddTagsResult {
  const { normalize = (raw: string) => raw.trim(), validate, maxTags, dedupeKey = (tag: string) => tag.toLowerCase() } = options
  const seen = new Set(current.map(dedupeKey))
  const added: string[] = []
  const rejected: string[] = []
  let error: string | null = null

  for (const raw of incoming) {
    const tag = normalize(raw)
    if (!tag) continue
    const key = dedupeKey(tag)
    if (seen.has(key)) continue

    const reason = validate?.(tag) ?? null
    if (reason) {
      rejected.push(tag)
      error ??= reason
      continue
    }
    if (maxTags !== undefined && current.length + added.length >= maxTags) {
      rejected.push(tag)
      error ??= `You can add at most ${maxTags}.`
      continue
    }
    seen.add(key)
    added.push(tag)
  }

  return { tags: added.length > 0 ? [...current, ...added] : (current as string[]), rejected, error }
}

/** `current` without the tag at `index` — unchanged (same array) for an out-of-range index. */
export function removeTagAt(current: readonly string[], index: number): string[] {
  if (index < 0 || index >= current.length) return current as string[]
  return [...current.slice(0, index), ...current.slice(index + 1)]
}

export interface TagInputProps extends AddTagsOptions {
  /** Id for the text input, so a `<Label htmlFor>` elsewhere labels it. */
  id?: string
  value: string[]
  onChange: (next: string[]) => void
  placeholder?: string
  disabled?: boolean
  /** Id(s) of helper text describing this input — merged with the input's own error message id. */
  "aria-describedby"?: string
  "aria-invalid"?: boolean
  /** Fired after a blur has committed any pending text (react-hook-form's `field.onBlur`, for touched state). */
  onBlur?: () => void
  /**
   * The not-yet-committed text currently in the input. A caller with its
   * own Save button (rather than a form submit) uses this to enable Save as
   * soon as something is typed — the pending text becomes a tag on blur,
   * which happens before the Save click lands.
   */
  onInputChange?: (text: string) => void
}

/**
 * Free-text chip input for an ordered list of short strings: type and press
 * Enter or comma to add, Backspace in an empty input removes the last chip,
 * each chip has its own remove button, and pasting a comma/newline-separated
 * list adds every item at once. Controlled (`value`/`onChange`); only the
 * half-typed text lives in local state. Chips are styled like
 * `RoleMultiSelect`'s selected roles so the two read as the same control
 * family — the difference being this one accepts anything typed, rather than
 * picking from a fixed option list.
 *
 * Whatever is still in the input when it loses focus is committed as a tag,
 * so typing a value and going straight to Save/Submit without pressing Enter
 * doesn't silently lose it. A refused entry (see `addTags`) stays in the
 * input with its reason shown underneath instead.
 */
export function TagInput({
  id,
  value,
  onChange,
  placeholder,
  disabled,
  normalize,
  validate,
  maxTags,
  dedupeKey,
  "aria-describedby": ariaDescribedBy,
  "aria-invalid": ariaInvalid,
  onBlur,
  onInputChange,
}: TagInputProps) {
  const generatedId = useId()
  const inputId = id ?? `${generatedId}-input`
  const errorId = `${inputId}-error`
  const inputRef = useRef<HTMLInputElement>(null)

  const [draft, setDraftState] = useState("")
  const [entryError, setEntryError] = useState<string | null>(null)
  // Screen readers get no other signal that Enter turned their text into a
  // chip (the input just empties) or that Backspace removed one.
  const [announcement, setAnnouncement] = useState("")

  const atLimit = maxTags !== undefined && value.length >= maxTags

  function setDraft(text: string) {
    setDraftState(text)
    onInputChange?.(text)
  }

  function commit(pieces: string[], leftover = "") {
    const result = addTags(value, pieces, { normalize, validate, maxTags, dedupeKey })
    if (result.tags !== value) {
      onChange(result.tags)
      setAnnouncement(`Added ${result.tags.slice(value.length).join(", ")}`)
    }
    setEntryError(result.error)
    setDraft([...result.rejected, leftover].filter(Boolean).join(", "))
  }

  function remove(index: number) {
    const removed = value[index]
    onChange(removeTagAt(value, index))
    setAnnouncement(`Removed ${removed}`)
    setEntryError(null)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    // Mid-IME-composition (Japanese, Chinese, Korean…), Enter confirms the
    // conversion and Backspace edits the reading — neither is meant for this
    // input. Acting on them would turn a half-composed "ちゅうもん" into a chip
    // and wipe the input under the IME. keyCode 229 covers Safari, which
    // fires the confirming Enter's keydown after compositionend, with
    // isComposing already false.
    if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
    if (event.key === "Enter" || event.key === ",") {
      // An empty Enter is left alone so it still submits a surrounding form,
      // the same as pressing Enter in any other text input there.
      if (event.key === "Enter" && !draft.trim()) return
      event.preventDefault()
      commit(splitTagText(draft))
      return
    }
    if (event.key === "Backspace" && draft === "" && value.length > 0) {
      event.preventDefault()
      remove(value.length - 1)
    }
  }

  function handlePaste(event: ClipboardEvent<HTMLInputElement>) {
    const pasted = event.clipboardData.getData("text")
    if (!TAG_SEPARATOR.test(pasted)) return // a single value: let the browser insert it normally
    event.preventDefault()
    const input = event.currentTarget
    const start = input.selectionStart ?? draft.length
    const end = input.selectionEnd ?? draft.length
    commit(splitTagText(draft.slice(0, start) + pasted + draft.slice(end)))
  }

  function handleChange(text: string, composing: boolean) {
    if (entryError) setEntryError(null)
    // A separator inside text that's still being composed isn't the user
    // finishing a tag yet; it's split out at the next Enter, comma or blur.
    if (composing) {
      setDraft(text)
      return
    }
    const { completed, remainder } = extractCompletedTags(text)
    if (completed.length > 0) {
      commit(completed, remainder)
    } else {
      setDraft(text)
    }
  }

  function handleBlur() {
    if (draft.trim()) commit(splitTagText(draft))
    onBlur?.()
  }

  const describedBy = [ariaDescribedBy, entryError ? errorId : null].filter(Boolean).join(" ") || undefined
  const inputPlaceholder = disabled
    ? value.length === 0 ? "None" : undefined
    : atLimit ? `Limit of ${maxTags} reached` : value.length === 0 ? placeholder : undefined

  return (
    <div>
      <div
        onClick={() => inputRef.current?.focus()}
        className={cn(
          "flex min-h-11 w-full flex-wrap items-center gap-1.5 rounded-btn border border-[var(--border-strong)] bg-muted px-2.5 py-2 transition-colors",
          "focus-within:border-primary focus-within:bg-card focus-within:ring-3 focus-within:ring-primary/15",
          (ariaInvalid || entryError) && "border-destructive focus-within:border-destructive focus-within:ring-destructive/20",
          disabled ? "cursor-not-allowed opacity-70" : "cursor-text"
        )}
      >
        {value.map((tag, index) => (
          <span
            key={tag}
            className="inline-flex max-w-full items-center gap-1.5 rounded-[6px] border border-primary bg-primary-tint py-[3px] pr-1.5 pl-2.5 text-[12px] font-semibold text-[var(--primary-dark,var(--primary))]"
          >
            <span className="break-all">{tag}</span>
            {!disabled && (
              <button
                type="button"
                onClick={(event) => {
                  event.stopPropagation()
                  remove(index)
                  inputRef.current?.focus()
                }}
                aria-label={`Remove ${tag}`}
                className="flex rounded-sm text-current outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
              >
                <X size={11} strokeWidth={3} />
              </button>
            )}
          </span>
        ))}
        {/* Rendered even when disabled (just inert) so a `<Label htmlFor>` pointing at `id` always has its target. */}
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          value={draft}
          disabled={disabled}
          onChange={(event) => handleChange(event.target.value, (event.nativeEvent as InputEvent).isComposing === true)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          onBlur={handleBlur}
          placeholder={inputPlaceholder}
          aria-invalid={Boolean(ariaInvalid || entryError) || undefined}
          aria-describedby={describedBy}
          className={cn(
            "flex-1 bg-transparent px-0.5 py-[3px] text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] disabled:cursor-not-allowed",
            disabled && value.length > 0 ? "w-0 min-w-0" : "min-w-[140px]"
          )}
        />
      </div>
      {entryError && (
        <p id={errorId} role="alert" className="mt-1.5 text-[12px] text-destructive">
          {entryError}
        </p>
      )}
      <span className="sr-only" aria-live="polite">
        {announcement}
      </span>
    </div>
  )
}
