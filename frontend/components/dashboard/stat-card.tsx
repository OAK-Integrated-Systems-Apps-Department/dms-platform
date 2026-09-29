import type { LucideIcon } from "lucide-react"

interface StatCardProps {
  icon: LucideIcon
  label: string
  value: string
  delta: string
  accent?: boolean
}

export function StatCard({ icon: Icon, label, value, delta, accent }: StatCardProps) {
  return (
    <div className="rounded-lg border border-border bg-card px-[18px] py-4 shadow-[var(--ledger-shadow)]">
      <div className="mb-3.5 flex items-center justify-between">
        <div
          className="flex size-8 items-center justify-center rounded-lg"
          style={{ background: accent ? "var(--gold-tint)" : "var(--primary-tint)" }}
        >
          <Icon size={16} color={accent ? "var(--gold)" : "var(--primary)"} />
        </div>
      </div>
      <div className="font-heading text-[26px] font-semibold">{value}</div>
      <div className="mt-0.5 text-[12.5px] text-muted-foreground">{label}</div>
      <div
        className="mt-2 text-[11px] font-semibold"
        style={{ color: accent ? "var(--gold)" : "var(--primary)" }}
      >
        {delta}
      </div>
    </div>
  )
}
