import { Fragment } from "react"

/** The token the autocomplete writes (A5): `@[Display Name](user:<uuid>)`. */
export const MENTION_TOKEN = /@\[([^\]\n]{1,150})\]\(user:([0-9a-fA-F-]{36})\)/g

/** A comment body with every mention token shown as a chip. */
export function MentionText({ body }: { body: string }) {
  const parts: React.ReactNode[] = []
  let last = 0
  for (const match of body.matchAll(MENTION_TOKEN)) {
    const index = match.index ?? 0
    if (index > last) parts.push(body.slice(last, index))
    parts.push(
      <span key={`${match[2]}-${index}`} className="rounded-sm bg-primary/10 px-1 font-semibold text-primary">
        @{match[1]}
      </span>
    )
    last = index + match[0].length
  }
  if (last < body.length) parts.push(body.slice(last))
  return (
    <span className="whitespace-pre-wrap">
      {parts.map((part, i) => (
        <Fragment key={i}>{part}</Fragment>
      ))}
    </span>
  )
}
