"use client"

import { useState } from "react"
import Link from "next/link"
import { MessageSquareReply, MessageSquareX } from "lucide-react"

import { formatDateTime } from "@/lib/utils"

import type { InfoRequestDto } from "@/lib/workflow/api"

import { RespondInfoDialog } from "./respond-info-dialog"

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
const BTN = "flex items-center gap-1 rounded-sm border px-2.5 py-1.5 text-[12px] font-semibold disabled:cursor-not-allowed disabled:opacity-50"

/** "Requests for me" - open information requests addressed to the caller, answered or declined in place. */
export function RequestsForMeTable({ rows }: { rows: InfoRequestDto[] }) {
  const [responding, setResponding] = useState<{ request: InfoRequestDto; mode: "respond" | "decline" } | null>(null)
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[880px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Document</th>
              <th className={TH}>Asked By</th>
              <th className={TH}>Request</th>
              <th className={TH}>Asked</th>
              <th className={`${TH} text-right`}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  Nobody is waiting on you for information.
                </td>
              </tr>
            )}
            {rows.map((r) => (
              <tr key={r.infoRequestId} className="border-b border-border align-top last:border-b-0">
                <td className="px-3 py-3">
                  <Link href={`/workflows/instances/${r.instanceId}`} className="text-[13.5px] font-semibold text-primary hover:underline">
                    {r.documentTitle ?? "A document"}
                  </Link>
                  {r.stageOrder != null && <div className="mt-0.5 text-[12px] text-muted-foreground">Stage {r.stageOrder}</div>}
                </td>
                <td className="px-3 py-3 text-[13px] text-foreground">{r.requestedBy.displayName ?? "—"}</td>
                <td className="max-w-[360px] px-3 py-3 text-[13px] whitespace-pre-wrap text-foreground">{r.message}</td>
                <td className="px-3 py-3">
                  <span className="font-mono text-[12.5px] text-muted-foreground">{formatDateTime(r.requestedAt)}</span>
                  {r.dueAt && <div className="mt-0.5 text-[12px] text-muted-foreground">answer by {formatDateTime(r.dueAt)}</div>}
                </td>
                <td className="px-3 py-3">
                  {r.status === "open" && (
                    <div className="flex flex-wrap justify-end gap-1.5">
                      <button type="button" onClick={() => setResponding({ request: r, mode: "respond" })} className={`${BTN} border-primary bg-primary text-primary-foreground`}>
                        <MessageSquareReply size={13} /> Answer
                      </button>
                      <button type="button" onClick={() => setResponding({ request: r, mode: "decline" })} className={`${BTN} border-[var(--rust)] bg-card text-[var(--rust)]`}>
                        <MessageSquareX size={13} /> Decline
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {responding && (
        <RespondInfoDialog request={responding.request} mode={responding.mode} onOpenChange={(o) => !o && setResponding(null)} />
      )}
    </div>
  )
}
