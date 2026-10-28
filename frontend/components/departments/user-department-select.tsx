"use client"

import { useState } from "react"
import { ChevronDown, Loader2 } from "lucide-react"

import { Label } from "@/components/ui/label"
import { useDepartmentOptions } from "@/lib/workflow/use-participant-options"
import { useUserDepartment } from "@/lib/departments/use-departments"

const SELECT_CLASS =
  "h-10 w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:cursor-not-allowed disabled:opacity-60"

/**
 * The user's primary department - also which department approver pool they
 * belong to. Saved on its own (PUT /api/v1/users/{id}/department), separate
 * from the profile form above it. The caller renders this only for someone
 * holding otua.manage-departments.update.
 */
export function UserDepartmentSelect({ userId }: { userId: string }) {
  const current = useUserDepartment(userId)
  const options = useDepartmentOptions()
  // null = untouched, follow the saved value.
  const [picked, setPicked] = useState<string | null>(null)

  const saved = current.data?.departmentId ?? ""
  const value = picked ?? saved
  const dirty = picked !== null && picked !== saved
  const missingSaved = Boolean(saved) && !(options.data ?? []).some((o) => o.value === saved)

  function handleSave() {
    current.assign(value || null, { onSuccess: () => setPicked(null) })
  }

  return (
    <div className="mt-5 max-w-[760px] rounded-lg border border-border bg-card px-[30px] py-[22px] shadow-[var(--ledger-shadow)]">
      <h2 className="mb-1 font-heading text-[16.5px] font-semibold">Department</h2>
      <p className="mb-4 text-[12.5px] text-muted-foreground">
        The user&apos;s primary department. Workflow stages routed to a department can be claimed by its members.
      </p>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex-1">
          <Label htmlFor="user-department">Department</Label>
          <div className="relative">
            <select
              id="user-department"
              value={value}
              onChange={(e) => setPicked(e.target.value)}
              disabled={current.isPending || options.isPending || current.isAssigning}
              className={SELECT_CLASS}
            >
              <option value="">{current.isPending || options.isPending ? "Loading…" : "No department"}</option>
              {missingSaved && <option value={saved}>{current.data?.departmentName ?? "Current department"}</option>}
              {(options.data ?? []).map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
            <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
          </div>
        </div>
        <button
          type="button"
          onClick={handleSave}
          disabled={!dirty || current.isAssigning}
          className="flex h-10 items-center gap-1.5 rounded-btn border border-primary bg-primary px-[18px] text-[13px] font-bold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          {current.isAssigning && <Loader2 size={14} className="animate-spin" />}
          Save Department
        </button>
      </div>
      {(current.isError || options.isError) && (
        <p className="mt-2 text-[12px] text-[var(--rust)]">Could not load departments.</p>
      )}
    </div>
  )
}
