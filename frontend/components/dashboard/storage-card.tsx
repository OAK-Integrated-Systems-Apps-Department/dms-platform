import { HardDrive } from "lucide-react"

interface StorageCardProps {
  usedGb: number
  totalGb: number
  usedPercent: number
}

export function StorageCard({ usedGb, totalGb, usedPercent }: StorageCardProps) {
  return (
    <div className="rounded-[14px] border border-border bg-card px-[18px] py-4 shadow-[var(--ledger-shadow)]">
      <div className="mb-3.5 flex items-center justify-between">
        <div
          className="flex size-8 items-center justify-center rounded-lg"
          style={{ background: "var(--primary-tint)" }}
        >
          <HardDrive size={16} color="var(--primary)" />
        </div>
      </div>
      <div className="font-heading text-[26px] font-semibold">
        {usedGb} GB{" "}
        <span className="font-sans text-sm font-normal text-muted-foreground">
          / {totalGb} GB
        </span>
      </div>
      <div className="mt-0.5 mb-2.5 text-[12.5px] text-muted-foreground">
        Storage used
      </div>
      <div className="h-1.5 overflow-hidden rounded-full border border-border bg-muted">
        <div
          className="h-full rounded-full bg-primary"
          style={{ width: `${usedPercent}%` }}
        />
      </div>
    </div>
  )
}
