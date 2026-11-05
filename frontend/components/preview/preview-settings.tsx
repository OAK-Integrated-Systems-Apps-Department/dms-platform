"use client"

import { useState, type ReactNode } from "react"
import Link from "next/link"
import { Loader2, RotateCw, ShieldCheck } from "lucide-react"

import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { TablePagination } from "@/components/ui/table-pagination"
import { Button } from "@/components/ui/button"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  isOpenPolicy,
  type PreviewMode,
  type PreviewPolicyDto,
} from "@/lib/preview/api"
import {
  usePreviewFailures,
  usePreviewPolicy,
  useRetryPreview,
  useSavePreviewPolicy,
} from "@/lib/preview/use-preview"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 10

const WATERMARK_OPTIONS: { value: PreviewMode; label: string }[] = [
  { value: "allow", label: "Allow" },
  { value: "watermark", label: "Watermark" },
  { value: "block", label: "Block" },
]
const COPY_OPTIONS: { value: "allow" | "block"; label: string }[] = [
  { value: "allow", label: "Allow" },
  { value: "block", label: "Block" },
]

/**
 * Document preview settings (DPRM): what people may do from a preview - print, download, copy - and the list of
 * previews the server could not make. Anyone with the preview-policy permissions can look; the buttons that change
 * something need them too, and the backend checks again.
 */
export function PreviewSettings() {
  return (
    <div className="flex w-full flex-col gap-6">
      <PolicyCard />
      <FailuresList />
    </div>
  )
}

function Section({
  title,
  hint,
  children,
}: {
  title: string
  hint: string
  children: ReactNode
}) {
  return (
    <section className="rounded-lg border border-border bg-card">
      <header className="border-b border-border px-5 py-3.5">
        <h2 className="text-[14.5px] font-semibold">{title}</h2>
        <p className="mt-0.5 text-[12.5px] text-muted-foreground">{hint}</p>
      </header>
      {children}
    </section>
  )
}

function Row({
  title,
  description,
  children,
}: {
  title: string
  description: ReactNode
  children: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 border-b border-border px-5 py-4 last:border-b-0">
      <div className="min-w-[220px] flex-1">
        <h3 className="text-[13.5px] font-medium">{title}</h3>
        <p className="mt-0.5 max-w-[560px] text-[12.5px] leading-[1.55] text-muted-foreground">
          {description}
        </p>
      </div>
      <div className="max-w-full">{children}</div>
    </div>
  )
}

// ---------------------------------------------------------------- the policy

function PolicyCard() {
  const { data, isPending, isError, error, refetch } = usePreviewPolicy()
  const { hasAnyPermission } = usePermissions()
  if (isPending) return <LoadingState message="Loading preview settings…" />
  if (isError || !data) {
    return (
      <LoadFailed
        error={error}
        fallback="Could not load the preview settings."
        onRetry={() => void refetch()}
      />
    )
  }
  return (
    <PolicyForm
      // Re-seeded if the saved policy changes under it (another administrator, or this one saving).
      key={JSON.stringify(data)}
      initial={data}
      canEdit={hasAnyPermission(
        PermissionCodes.DPRM_RESTRICT_PRINT_DOWNLOAD,
        PermissionCodes.DPRM_TOGGLE_PREVIEW_WATERMARK
      )}
    />
  )
}

function PolicyForm({
  initial,
  canEdit,
}: {
  initial: PreviewPolicyDto
  canEdit: boolean
}) {
  const save = useSavePreviewPolicy()
  const [draft, setDraft] = useState<PreviewPolicyDto>(initial)
  const changed = JSON.stringify(draft) !== JSON.stringify(initial)
  const locked = !canEdit || save.isPending

  return (
    <Section
      title="What people can do from a preview"
      hint="Applies to every preview in your organisation, whatever a person's own permission on the document."
    >
      <Row
        title="Print"
        description={
          <>
            <strong>Watermark</strong> prints the pages with the viewer&apos;s
            name and the time on them. <strong>Block</strong> turns printing
            from a preview off.
          </>
        }
      >
        <SegmentedControl
          aria-label="Print"
          value={draft.print}
          onChange={(print) => setDraft({ ...draft, print })}
          disabled={locked}
          options={WATERMARK_OPTIONS}
        />
      </Row>
      <Row
        title="Download"
        description={
          <>
            <strong>Watermark</strong> gives the viewer a PDF of the pages with
            their name and the time on every page, with no text to copy.{" "}
            <strong>Block</strong> removes the Download button from previews. A
            file that can&apos;t carry a watermark is never sent plain.
          </>
        }
      >
        <SegmentedControl
          aria-label="Download"
          value={draft.download}
          onChange={(download) => setDraft({ ...draft, download })}
          disabled={locked}
          options={WATERMARK_OPTIONS}
        />
      </Row>
      <Row
        title="Copy text"
        description="Block stops people selecting and copying text out of a preview."
      >
        <SegmentedControl
          aria-label="Copy text"
          value={draft.copy}
          onChange={(copy) => setDraft({ ...draft, copy })}
          disabled={locked}
          options={COPY_OPTIONS}
        />
      </Row>
      <div className="flex flex-wrap items-center gap-3 border-t border-border bg-muted/30 px-5 py-3.5">
        <p className="flex min-w-[260px] flex-1 items-start gap-2 text-[12.5px] leading-[1.55] text-muted-foreground">
          <ShieldCheck
            size={15}
            aria-hidden="true"
            className="mt-0.5 shrink-0 text-primary"
          />
          {isOpenPolicy(draft)
            ? "Nothing is restricted: previews show the file as it is."
            : "While anything is restricted, previews are drawn on the server as pictures of the pages: nobody can select their text, and the file itself isn't sent to the browser. Files that can't be drawn this way can't be previewed. Downloading from the file list still follows each person's own permission."}
        </p>
        <Button
          type="button"
          variant="outline"
          disabled={!changed || locked}
          onClick={() => setDraft(initial)}
        >
          Reset
        </Button>
        <Button
          type="button"
          disabled={!changed || locked}
          onClick={() => save.mutate(draft)}
        >
          {save.isPending && <Loader2 className="animate-spin" />}
          Save changes
        </Button>
      </div>
    </Section>
  )
}

// ---------------------------------------------------------------- failures

function FailuresList() {
  const [page, setPage] = useState(1)
  const failures = usePreviewFailures(page - 1, PAGE_SIZE)
  const retry = useRetryPreview()

  return (
    <Section
      title="Previews that could not be made"
      hint="A file that can't be converted or drawn shows a placeholder, and its details stay visible. It is tried again on its own after a while; retry it here once the cause is fixed."
    >
      {failures.isPending ? (
        <LoadingState message="Loading…" />
      ) : failures.isError ? (
        <div className="p-4">
          <LoadFailed
            error={failures.error}
            fallback="Could not load the failed previews."
            onRetry={() => void failures.refetch()}
          />
        </div>
      ) : failures.data.content.length === 0 ? (
        <p className="px-6 py-10 text-center text-[13px] text-muted-foreground">
          Every preview has been made - nothing needs review.
        </p>
      ) : (
        <>
          <ul className="divide-y divide-border">
            {failures.data.content.map((failure) => (
              <li
                key={failure.versionId}
                className="flex flex-wrap items-center gap-3 px-5 py-3"
              >
                <div className="min-w-0 flex-1">
                  <Link
                    href={`/documents/${failure.documentId}`}
                    className="text-[13.5px] font-semibold hover:underline"
                  >
                    {failure.title}
                  </Link>
                  <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                    Version {failure.versionNumber} · {failure.fileName} ·
                    failed {formatDateTime(failure.failedAt)}
                  </p>
                  <p className="mt-0.5 text-[12.5px] text-[var(--rust)]">
                    {failure.reason}
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  disabled={retry.isPending}
                  onClick={() => retry.mutate(failure.versionId)}
                >
                  <RotateCw />
                  Try again
                </Button>
              </li>
            ))}
          </ul>
          {failures.data.totalPages > 1 && (
            <div className="border-t border-border p-3">
              <TablePagination
                currentPage={page}
                totalPages={failures.data.totalPages}
                onPageChange={setPage}
              />
            </div>
          )}
        </>
      )}
    </Section>
  )
}
