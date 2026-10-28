"use client"

import { Badge } from "@/components/ui/badge"

import type { DepartmentMemberDto } from "@/lib/departments/api"

/**
 * A department's direct members - also its approver pool when a workflow
 * stage routes to it. Membership is set per user, from the Edit User
 * screen's Department select.
 */
export function DepartmentMembers({ members, loading }: { members: DepartmentMemberDto[]; loading: boolean }) {
  if (loading) return <p className="pt-3 text-[13px] text-muted-foreground">Loading members…</p>
  if (members.length === 0) {
    return (
      <p className="pt-3 text-[13px] text-muted-foreground">
        No members yet. Assign users to this department from their Edit User page.
      </p>
    )
  }
  return (
    <ul className="divide-y divide-border pt-2">
      {members.map((member) => (
        <li key={member.userId} className="flex items-center justify-between gap-3 py-2.5">
          <div className="min-w-0">
            <p className="truncate text-[13.5px] font-medium text-foreground">{member.displayName}</p>
            <p className="truncate text-[12px] text-muted-foreground">{member.email}</p>
          </div>
          <Badge variant={member.status === "active" ? "default" : "muted"}>{member.status}</Badge>
        </li>
      ))}
    </ul>
  )
}
