"use client"

import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { Input } from "@/components/ui/input"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import {
  fetchPresetLimit,
  fetchSearchAdminStatus,
  requestReconcile,
  requestReindex,
  savePresetLimit,
  type SearchAdminStatusDto,
} from "@/lib/admin/search-api"
import { ApiError, friendlyMessage } from "@/lib/api-error"

const STATUS_KEY = ["admin", "search", "status"] as const
const LIMIT_KEY = ["admin", "search", "preset-limit"] as const
const POLL_MS = 10_000

const ENGINE_TEXT: Record<SearchAdminStatusDto["engine"], string> = {
  up: "Running",
  down: "Not reachable. Search is using the basic title search.",
  red: "Unhealthy. Search is using the basic title search.",
}

const BACKFILL_TEXT: Record<SearchAdminStatusDto["backfillState"], string> = {
  none: "Not started",
  running: "In progress",
  done: "Complete",
  failed: "Failed",
}

function formatLag(seconds: number) {
  if (seconds < 60) return `${seconds} s`
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`
}

/** The administrator's view of search for this organisation: health, the index, reindex, the check, and the preset limit. */
export function SearchStatus() {
  const queryClient = useQueryClient()
  const status = useQuery({
    queryKey: STATUS_KEY,
    queryFn: fetchSearchAdminStatus,
    retry: false,
    // While a reindex or a check is running, keep watching it.
    refetchInterval: (query) => (query.state.data?.reindexRunning || query.state.data?.reconcileRunning ? POLL_MS : false),
  })
  const [confirmReindex, setConfirmReindex] = useState(false)

  const reconcile = useMutation({
    mutationFn: requestReconcile,
    onSuccess: () => toast.success("Started a check of the search index."),
    onError: (error) => toast.error(refusal(error, "RECONCILE", "Could not start the check.")),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: STATUS_KEY }),
  })

  async function reindex(): Promise<string | null> {
    try {
      await requestReindex()
      toast.success("Reindex started. Search keeps working while it runs.")
      void queryClient.invalidateQueries({ queryKey: STATUS_KEY })
      return null
    } catch (error) {
      return refusal(error, "REINDEX", "Could not start the reindex.")
    }
  }

  if (status.isPending) return <LoadingState message="Loading the search status…" />
  if (status.isError) {
    return <LoadFailed error={status.error} fallback="Could not load the search status." onRetry={() => status.refetch()} />
  }
  const s = status.data
  const reindexBusy = s.reindexRunning === true || s.backfillState === "running"
  const reconcileBusy = s.reconcileRunning === true

  return (
    <div className="grid gap-5">
      <section className="rounded-lg border border-border bg-card p-5 shadow-[var(--ledger-shadow)]" aria-label="Search status">
        <h2 className="mb-3 font-heading text-[17px] font-semibold">Search status</h2>
        <dl className="grid gap-x-8 gap-y-2.5 text-[13.5px] sm:grid-cols-2">
          <Row label="Search engine" value={ENGINE_TEXT[s.engine]} />
          <Row label="Search switched on" value={s.searchEnabled ? "Yes" : "No"} />
          <Row label="Index ready" value={s.indexReady ? "Yes" : "No: search uses the basic title search until it is ready"} />
          <Row label="Reindex" value={BACKFILL_TEXT[s.backfillState]} />
          <Row label="Index generation" value={String(s.generation)} />
          <Row label="Waiting to be indexed" value={String(s.pendingJobs)} />
          <Row label="Failed" value={String(s.failedJobs)} />
          <Row label="Oldest waiting change" value={s.pendingJobs === 0 ? "None waiting" : formatLag(s.lagSeconds)} />
          <Row
            label="Last check"
            value={
              s.lastReconciledAt
                ? `${new Date(s.lastReconciledAt).toLocaleString()}${
                    s.gap ? `: ${s.gap.missing} missing, ${s.gap.orphans} extra` : ""
                  }`
                : "Never run"
            }
          />
        </dl>

        {s.reindexRecommended && (
          <p role="status" className="mt-4 rounded-lg border border-[var(--gold)]/40 bg-[var(--gold-tint)] px-4 py-2.5 text-[13px]">
            Searchable metadata fields changed since the last reindex. Run a reindex so existing documents use the new settings.
          </p>
        )}

        <div className="mt-5 flex flex-wrap items-center gap-2.5">
          <Button type="button" disabled={reindexBusy} onClick={() => setConfirmReindex(true)}>
            {reindexBusy ? "Reindex running…" : "Reindex"}
          </Button>
          <Button type="button" variant="outline" disabled={reconcileBusy || reconcile.isPending} onClick={() => reconcile.mutate()}>
            {reconcileBusy ? "Check running…" : s.lastReconciledAt ? "Run check now" : "Run now"}
          </Button>
        </div>
      </section>

      <PresetLimit />

      <ConfirmActionDialog
        open={confirmReindex}
        onOpenChange={setConfirmReindex}
        title="Reindex all documents?"
        description="This re-reads every document of your organisation. Search keeps working meanwhile and results update gradually."
        confirmLabel="Start reindex"
        pendingLabel="Starting…"
        cancelLabel="Cancel"
        onConfirm={reindex}
      />
    </div>
  )
}

/** The plain-words answer to a 409 (already running) or 429 (too soon), or the server's own message. */
function refusal(error: unknown, kind: "REINDEX" | "RECONCILE", fallback: string): string {
  if (error instanceof ApiError && error.status === 409) {
    return kind === "REINDEX" ? "A reindex is already running." : "A check is already running."
  }
  if (error instanceof ApiError && error.status === 429) {
    return "That was started a moment ago. Wait a few minutes before trying again."
  }
  return friendlyMessage(error, fallback)
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col">
      <dt className="text-[12px] text-muted-foreground">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </div>
  )
}

/** How many presets one person may keep in this organisation. */
function PresetLimit() {
  const queryClient = useQueryClient()
  const limit = useQuery({ queryKey: LIMIT_KEY, queryFn: fetchPresetLimit, retry: false })
  const [draft, setDraft] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: (value: number) => savePresetLimit(value),
    onSuccess: () => {
      toast.success("Preset limit saved.")
      setDraft(null)
      void queryClient.invalidateQueries({ queryKey: LIMIT_KEY })
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not save the preset limit.")),
  })

  if (limit.isPending) return null
  if (limit.isError) {
    return <LoadFailed error={limit.error} fallback="Could not load the preset limit." onRetry={() => limit.refetch()} />
  }
  const shown = draft ?? String(limit.data.maxPerUser)
  const value = Number(shown)
  const valid = Number.isInteger(value) && value >= 1 && value <= 500

  return (
    <section className="rounded-lg border border-border bg-card p-5 shadow-[var(--ledger-shadow)]" aria-label="Saved presets limit">
      <h2 className="mb-1 font-heading text-[17px] font-semibold">Saved presets</h2>
      <p className="mb-3 text-[13px] text-muted-foreground">
        How many saved searches each person may keep (1 to 500). The default is {limit.data.defaultMax}. Lowering it does not
        remove anyone&rsquo;s presets; it only stops them adding more until they are under it.
      </p>
      <form
        className="flex items-end gap-2.5"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) save.mutate(value)
        }}
      >
        <label className="grid gap-1 text-[13px] font-medium">
          Presets per person
          <Input
            type="number"
            min={1}
            max={500}
            className="w-32"
            value={shown}
            onChange={(e) => setDraft(e.target.value)}
            aria-invalid={!valid}
          />
        </label>
        <Button type="submit" disabled={!valid || save.isPending || draft === null || value === limit.data.maxPerUser}>
          {save.isPending ? "Saving…" : "Save"}
        </Button>
      </form>
      {!valid && (
        <p role="alert" className="mt-2 text-[12px] text-destructive">
          Enter a whole number from 1 to 500.
        </p>
      )}
    </section>
  )
}
