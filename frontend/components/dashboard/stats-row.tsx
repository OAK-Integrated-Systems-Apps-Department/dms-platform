import { ClipboardList, FileText, Upload } from "lucide-react"

import { StatCard } from "./stat-card"
import { StorageCard } from "./storage-card"

/** The 4-up row: 3 KPI cards + the storage usage card. Remove/reorder here. */
export function StatsRow() {
  return (
    <div className="mb-[30px] grid grid-cols-2 gap-4 lg:grid-cols-4">
      <StatCard icon={FileText} label="Total documents" value="4,286" delta="+126 this month" />
      <StatCard
        icon={ClipboardList}
        label="Pending my approval"
        value="3"
        delta="1 due today"
        accent
      />
      <StatCard icon={Upload} label="Recent uploads" value="18" delta="Last 7 days" />
      <StorageCard usedGb={68} totalGb={100} usedPercent={68} />
    </div>
  )
}
