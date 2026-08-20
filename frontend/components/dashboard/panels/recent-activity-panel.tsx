import { activityFeed } from "@/components/dashboard/data"
import { Panel } from "@/components/ui/panel"

/** Self-contained — delete this file's import from the page to remove the panel. */
export function RecentActivityPanel() {
  return (
    <Panel title="Recent activity">
      <div className="relative pl-1.5">
        {activityFeed.map((entry, index) => {
          const Icon = entry.icon
          const isLast = index === activityFeed.length - 1
          return (
            <div
              key={entry.id}
              className="relative flex gap-3"
              style={{ paddingBottom: isLast ? 0 : 18 }}
            >
              {!isLast && (
                <div className="absolute top-7 bottom-0 left-[13px] w-px bg-border" />
              )}
              <div className="z-10 flex size-[27px] shrink-0 items-center justify-center rounded-full border border-border bg-muted">
                <Icon size={13} color="var(--primary)" />
              </div>
              <div className="pt-[3px]">
                <div className="text-[13px]">
                  <b>{entry.who}</b> {entry.action}{" "}
                  <span className="text-muted-foreground">{entry.target}</span>
                </div>
                <div className="mt-0.5 text-[11.5px] text-[var(--ink-faint)]">
                  {entry.time}
                </div>
              </div>
            </div>
          )
        })}
      </div>
    </Panel>
  )
}
