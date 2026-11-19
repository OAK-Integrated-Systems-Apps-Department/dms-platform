import { Fragment } from "react"

const ENTITIES: Record<string, string> = {
  "&amp;": "&",
  "&lt;": "<",
  "&gt;": ">",
  "&quot;": '"',
  "&#x27;": "'",
  "&#39;": "'",
  "&#x2F;": "/",
}

function decode(text: string): string {
  return text.replace(/&(?:amp|lt|gt|quot|#x27|#39|#x2F);/g, (m) => ENTITIES[m] ?? m)
}

/**
 * A search snippet. The server HTML-escapes the document's text and wraps matches in `<mark>`; this renders that as React
 * nodes, never as HTML: the only markup honoured is a literal `<mark>…</mark>` pair (split out and rendered as an element)
 * and everything else, however it is spelled, is plain text. A hostile document cannot inject markup through here.
 */
export function Snippet({ html, className }: { html: string; className?: string }) {
  const parts = html.split(/(<mark>[\s\S]*?<\/mark>)/g)
  return (
    <span className={className}>
      {parts.map((part, i) => {
        const marked = /^<mark>([\s\S]*)<\/mark>$/.exec(part)
        return marked ? (
          <mark key={i} className="rounded-sm bg-[var(--gold-tint)] px-0.5 text-foreground">
            {decode(marked[1])}
          </mark>
        ) : (
          <Fragment key={i}>{decode(part)}</Fragment>
        )
      })}
    </span>
  )
}
