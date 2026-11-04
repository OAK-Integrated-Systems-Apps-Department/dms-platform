"use client"

import { useState } from "react"
import { ChevronRight, LifeBuoy, SearchX } from "lucide-react"

import {
  FILE_TYPES,
  HELP_TOPICS,
  SHORTCUTS,
  type HelpArticle,
  type HelpTopic,
} from "@/components/help/help-content"
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion"
import { IconTile } from "@/components/ui/icon-tile"
import { SearchInput } from "@/components/ui/search-input"

/** Shortcuts under the search box to the questions people ask most - short labels, one line. */
const POPULAR = [
  { id: "how-to-upload", label: "Uploading files" },
  { id: "scanning", label: "Virus scanning" },
  { id: "manage-access", label: "Sharing and access" },
  { id: "deleted", label: "Recycle Bin" },
]

interface Found {
  topic: HelpTopic
  article: HelpArticle
}

const ALL: Found[] = HELP_TOPICS.flatMap((topic) =>
  topic.articles.map((article) => ({ topic, article }))
)

/** Every word of the search must appear in the question, answer or keywords. */
function matches({ topic, article }: Found, words: string[]): boolean {
  const text = [
    topic.title,
    article.question,
    article.answer.join(" "),
    article.keywords ?? "",
  ]
    .join(" ")
    .toLowerCase()
  return words.every((word) => text.includes(word))
}

/** A topic's icon on a small coloured tile. */
function TopicIcon({ topic, size = 36 }: { topic: HelpTopic; size?: number }) {
  return <IconTile icon={topic.icon} color={topic.color} size={size} />
}

/**
 * The Help page: a search over every answer, topics to browse, the answers
 * themselves (questions that open), a quick reference of file types, limits
 * and keyboard shortcuts, and where to go next. The content lives in
 * help-content.ts. Columns follow the space the page actually has (container
 * queries), so it fits beside the sidebar on any screen.
 */
export function HelpCenter() {
  const [query, setQuery] = useState("")
  const [open, setOpen] = useState<string[]>([])
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  const results = words.length
    ? ALL.filter((found) => matches(found, words))
    : []

  function show(articleId: string) {
    setQuery("")
    setOpen((current) =>
      current.includes(articleId) ? current : [...current, articleId]
    )
    // Once the topic sections are back, bring the question into view.
    requestAnimationFrame(() =>
      document
        .getElementById(`q-${articleId}`)
        ?.scrollIntoView({ behavior: "smooth", block: "center" })
    )
  }

  return (
    <div className="@container w-full">
      {/* Search first - most people arrive with a question. */}
      <section
        aria-labelledby="help-title"
        className="mb-8 rounded-2xl border border-border px-5 py-8 text-center shadow-[var(--ledger-shadow)] @2xl:px-10 @2xl:py-10"
        style={{
          background:
            "radial-gradient(120% 140% at 0% 0%, var(--primary-tint) 0%, transparent 55%), linear-gradient(135deg, var(--card) 0%, var(--secondary) 100%)",
        }}
      >
        <p className="mb-2 font-mono text-[11px] font-medium tracking-[0.2em] text-primary">
          HELP CENTRE
        </p>
        <h1
          id="help-title"
          className="font-heading text-[26px] leading-tight font-semibold tracking-[-0.01em] @2xl:text-[32px]"
        >
          How can we help?
        </h1>
        <p className="mx-auto mt-2 max-w-[480px] text-[14px] text-muted-foreground">
          Search the answers, or browse the topics below.
        </p>
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder="Search help, e.g. upload, versions, share"
          aria-label="Search help"
          className="mx-auto mt-6 max-w-[560px]"
          inputClassName="h-12 rounded-xl bg-card pl-11 text-[14.5px] shadow-[var(--ledger-shadow)]"
        />
        <div className="mx-auto mt-4 flex max-w-[640px] flex-wrap items-center justify-center gap-x-1.5 gap-y-2 text-[12.5px]">
          <span className="mr-1 text-muted-foreground">Popular:</span>
          {POPULAR.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => show(item.id)}
              className="rounded-full border border-border bg-card/80 px-3 py-1 font-medium whitespace-nowrap transition-colors hover:border-[var(--border-strong)] hover:text-primary focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              {item.label}
            </button>
          ))}
        </div>
      </section>

      {words.length > 0 ? (
        <SearchResults
          results={results}
          query={query.trim()}
          open={open}
          onOpenChange={setOpen}
        />
      ) : (
        <>
          {/* Browse by topic. */}
          <section aria-labelledby="topics-title" className="mb-10">
            <h2
              id="topics-title"
              className="mb-3 font-heading text-[17px] font-semibold"
            >
              Browse by topic
            </h2>
            <ul className="grid grid-cols-1 gap-3 @xl:grid-cols-2 @4xl:grid-cols-3 @6xl:grid-cols-4">
              {HELP_TOPICS.map((topic) => (
                <li key={topic.id}>
                  <a
                    href={`#${topic.id}`}
                    className="group flex h-full items-start gap-3 rounded-xl border border-border bg-card p-4 shadow-[var(--ledger-shadow)] transition-[transform,border-color] duration-200 hover:-translate-y-0.5 hover:border-[var(--border-strong)] focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
                  >
                    <TopicIcon topic={topic} />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center justify-between gap-2">
                        <span className="text-[13.5px] font-semibold group-hover:text-primary">
                          {topic.title}
                        </span>
                        <ChevronRight
                          size={15}
                          aria-hidden="true"
                          className="shrink-0 text-[var(--ink-faint)] transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                        />
                      </span>
                      <span className="mt-0.5 block text-[12.5px] text-muted-foreground">
                        {topic.summary}
                      </span>
                      <span className="mt-1.5 block text-[11px] text-[var(--ink-faint)]">
                        {topic.articles.length}{" "}
                        {topic.articles.length === 1 ? "article" : "articles"}
                      </span>
                    </span>
                  </a>
                </li>
              ))}
            </ul>
          </section>

          {/* The answers, topic by topic. */}
          <div className="flex flex-col gap-8">
            {HELP_TOPICS.map((topic) => (
              <section
                key={topic.id}
                id={topic.id}
                aria-labelledby={`${topic.id}-title`}
                className="scroll-mt-24"
              >
                <div className="mb-3 flex items-center gap-3">
                  <TopicIcon topic={topic} size={30} />
                  <div>
                    <h2
                      id={`${topic.id}-title`}
                      className="font-heading text-[17px] leading-tight font-semibold"
                    >
                      {topic.title}
                    </h2>
                    <p className="text-[12.5px] text-muted-foreground">
                      {topic.summary}
                    </p>
                  </div>
                </div>
                <Articles
                  articles={topic.articles}
                  open={open}
                  onOpenChange={setOpen}
                />
              </section>
            ))}

            <QuickReference />
            <StillNeedHelp />
          </div>
        </>
      )}
    </div>
  )
}

function Articles({
  articles,
  open,
  onOpenChange,
  topicOf,
}: {
  articles: HelpArticle[]
  open: string[]
  onOpenChange: (open: string[]) => void
  /** Shown above each question - for search results, which mix topics. */
  topicOf?: (article: HelpArticle) => string
}) {
  return (
    <Accordion
      multiple
      value={open}
      onValueChange={(value) => onOpenChange(value as string[])}
      className="rounded-xl border border-border bg-card px-4 shadow-[var(--ledger-shadow)] @2xl:px-5"
    >
      {articles.map((article) => (
        <AccordionItem
          key={article.id}
          value={article.id}
          id={`q-${article.id}`}
          className="scroll-mt-24 border-border"
        >
          <AccordionTrigger className="py-3.5 text-[14px] font-semibold hover:text-primary hover:no-underline">
            <span>
              {topicOf && (
                <span className="mb-0.5 block text-[11px] font-medium tracking-wide text-muted-foreground uppercase">
                  {topicOf(article)}
                </span>
              )}
              {article.question}
            </span>
          </AccordionTrigger>
          <AccordionContent className="pb-4 text-[13.5px] leading-relaxed text-muted-foreground">
            <Answer lines={article.answer} />
          </AccordionContent>
        </AccordionItem>
      ))}
    </Accordion>
  )
}

/** Paragraphs, with "• " lines grouped into a list. */
function Answer({ lines }: { lines: string[] }) {
  const blocks: (string | string[])[] = []
  for (const line of lines) {
    if (line.startsWith("• ")) {
      const last = blocks[blocks.length - 1]
      if (Array.isArray(last)) last.push(line.slice(2))
      else blocks.push([line.slice(2)])
    } else {
      blocks.push(line)
    }
  }
  return (
    <div className="flex flex-col gap-2.5">
      {blocks.map((block, i) =>
        Array.isArray(block) ? (
          <ul key={i} className="ml-4 list-disc space-y-1 marker:text-primary">
            {block.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        ) : (
          <p key={i}>{block}</p>
        )
      )}
    </div>
  )
}

function SearchResults({
  results,
  query,
  open,
  onOpenChange,
}: {
  results: Found[]
  query: string
  open: string[]
  onOpenChange: (open: string[]) => void
}) {
  if (results.length === 0) {
    return (
      <div className="flex flex-col items-center rounded-2xl border border-dashed border-border px-6 py-14 text-center">
        <span className="mb-3 flex size-11 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
          <SearchX size={20} aria-hidden="true" />
        </span>
        <p className="text-[14px] font-semibold">
          No answers match &ldquo;{query}&rdquo;
        </p>
        <p className="mt-1 max-w-[380px] text-[13px] text-muted-foreground">
          Try fewer or different words - for example upload, folder, version or
          access.
        </p>
      </div>
    )
  }
  const topicById = new Map(results.map((r) => [r.article.id, r.topic.title]))
  return (
    <section aria-label="Search results">
      <p aria-live="polite" className="mb-3 text-[13px] text-muted-foreground">
        {`${results.length} ${results.length === 1 ? "answer" : "answers"} for “${query}”`}
      </p>
      <Articles
        articles={results.map((r) => r.article)}
        open={open}
        onOpenChange={onOpenChange}
        topicOf={(article) => topicById.get(article.id) ?? ""}
      />
    </section>
  )
}

const card =
  "rounded-xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)]"

function QuickReference() {
  return (
    <section
      id="reference"
      aria-labelledby="reference-title"
      className="scroll-mt-24"
    >
      <h2
        id="reference-title"
        className="mb-3 font-heading text-[17px] font-semibold"
      >
        Quick reference
      </h2>
      <div className="grid gap-4 @4xl:grid-cols-[1.35fr_1fr]">
        <div className={card}>
          <h3 className="mb-3 text-[13.5px] font-semibold">
            Supported file types
          </h3>
          <dl className="flex flex-col gap-2.5">
            {FILE_TYPES.map((group) => (
              <div
                key={group.group}
                className="grid gap-1.5 @xl:grid-cols-[130px_1fr] @xl:items-baseline"
              >
                <dt className="text-[12px] text-muted-foreground">
                  {group.group}
                </dt>
                <dd className="flex flex-wrap gap-1.5">
                  {group.types.map((type) => (
                    <span
                      key={type}
                      className="rounded-md border border-border bg-muted px-1.5 py-0.5 font-mono text-[11px]"
                    >
                      .{type}
                    </span>
                  ))}
                </dd>
              </div>
            ))}
          </dl>
        </div>

        <div className="flex flex-col gap-4">
          <div className={card}>
            <h3 className="mb-3 text-[13.5px] font-semibold">Limits</h3>
            <dl className="flex flex-col gap-2 text-[12.5px]">
              {[
                ["Largest file", "No limit"],
                ["Recycle Bin keeps documents for", "30 days (by default)"],
                ["Password-protected files", "Can't be uploaded"],
              ].map(([term, value]) => (
                <div
                  key={term}
                  className="flex items-baseline justify-between gap-4 border-b border-border pb-2 last:border-b-0 last:pb-0"
                >
                  <dt className="text-muted-foreground">{term}</dt>
                  <dd className="text-right font-semibold">{value}</dd>
                </div>
              ))}
            </dl>
          </div>

          <div className={card}>
            <h3 className="mb-3 text-[13.5px] font-semibold">
              Keyboard shortcuts
            </h3>
            <dl className="flex flex-col gap-2 text-[12.5px]">
              {SHORTCUTS.map((shortcut) => (
                <div
                  key={shortcut.action}
                  className="flex items-center justify-between gap-4"
                >
                  <dt className="text-muted-foreground">{shortcut.action}</dt>
                  <dd className="flex shrink-0 gap-1">
                    {shortcut.keys.map((key) => (
                      <kbd
                        key={key}
                        className="min-w-6 rounded-md border border-[var(--border-strong)] bg-muted px-1.5 py-0.5 text-center font-mono text-[11px] shadow-[0_1px_0_var(--border-strong)]"
                      >
                        {key}
                      </kbd>
                    ))}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
        </div>
      </div>
    </section>
  )
}

function StillNeedHelp() {
  return (
    <section
      aria-labelledby="contact-title"
      className="flex flex-col items-start gap-4 rounded-2xl border border-border p-6 shadow-[var(--ledger-shadow)] @xl:flex-row @xl:items-center"
      style={{
        background:
          "linear-gradient(135deg, var(--primary-tint), var(--card) 70%)",
      }}
    >
      <span
        aria-hidden="true"
        className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground"
      >
        <LifeBuoy size={20} />
      </span>
      <div>
        <h2
          id="contact-title"
          className="font-heading text-[16px] font-semibold"
        >
          Still need help?
        </h2>
        <p className="mt-1 max-w-[640px] text-[13px] text-muted-foreground">
          Contact your system administrator. To help them sort it out quickly,
          include what you were trying to do, the document or page involved, and
          roughly when it happened.
        </p>
      </div>
    </section>
  )
}
