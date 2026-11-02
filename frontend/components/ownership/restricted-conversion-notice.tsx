import { Lock } from "lucide-react"

interface RestrictedConversionNoticeProps {
  /** Documents switched (or about to be) to Restricted so the new owner can open them. */
  count: number
  /** The count is for the first step of a split transfer only. */
  partial?: boolean
  /** Past tense, for the results. */
  done?: boolean
}

/**
 * Why a document switches to Restricted: it sits in a folder the new owner can't open, so it gets its own
 * access list - the folder's people stay on it and the new owner is added as manager. Shown before confirming
 * and again in the results. Renders nothing for zero.
 */
export function RestrictedConversionNotice({ count, partial, done }: RestrictedConversionNoticeProps) {
  if (count <= 0) return null
  const documents = count === 1 ? "1 document" : `${count.toLocaleString()} documents`
  return (
    <div
      role="note"
      className="flex items-start gap-2 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2.5 text-[12.5px] text-[var(--gold-foreground)]"
    >
      <Lock size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
      <p>
        <strong className="font-semibold">
          {documents} {done ? "were" : "will be"} switched to Restricted
        </strong>
        {partial && !done ? " in the first step" : ""}
        {" "}because the new owner could not otherwise open {count === 1 ? "it" : "them"}. {count === 1 ? "It keeps" : "They keep"} the
        same people as before and the new owner is added as manager.
      </p>
    </div>
  )
}
