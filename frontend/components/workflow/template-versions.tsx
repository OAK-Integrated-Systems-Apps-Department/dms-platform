"use client"

import Link from "next/link"

import { Badge } from "@/components/ui/badge"
import { useWorkflowTemplateVersions } from "@/lib/workflow/use-workflow-template"
import { TEMPLATE_STATUS_BADGE_VARIANT, TEMPLATE_STATUS_LABELS } from "@/lib/workflow/workflow-badges"
import { cn, formatDateTime } from "@/lib/utils"

/** Every version in this template's family, newest first, each linking to its own template page. */
export function TemplateVersions({ templateId }: { templateId: string }) {
  const { data, isPending, isError } = useWorkflowTemplateVersions(templateId)

  if (isPending) return <p className="pt-3 text-[13px] text-muted-foreground">Loading versions…</p>
  if (isError) return <p className="pt-3 text-[13px] text-[var(--rust)]">Could not load this template&apos;s versions.</p>

  const versions = data.slice().sort((a, b) => b.versionNumber - a.versionNumber)

  return (
    <ul className="divide-y divide-border pt-2">
      {versions.map((version) => {
        const current = version.templateId === templateId
        return (
          <li key={version.templateId}>
            <Link
              href={`/admin/workflows/${version.templateId}`}
              aria-current={current ? "page" : undefined}
              className={cn(
                "flex items-center justify-between gap-3 rounded-sm px-2 py-2.5 hover:bg-muted",
                current && "bg-primary/5"
              )}
            >
              <span className="flex items-center gap-2.5">
                <span className={cn("text-[13.5px] font-semibold", current ? "text-primary" : "text-foreground")}>
                  v{version.versionNumber}
                </span>
                <Badge variant={TEMPLATE_STATUS_BADGE_VARIANT[version.status]}>{TEMPLATE_STATUS_LABELS[version.status]}</Badge>
                <span className="text-[12px] text-muted-foreground">
                  {version.stageCount} {version.stageCount === 1 ? "stage" : "stages"}
                </span>
              </span>
              <span className="font-mono text-[12px] text-muted-foreground">
                {formatDateTime(version.updatedAt ?? version.createdAt)}
              </span>
            </Link>
          </li>
        )
      })}
    </ul>
  )
}
