"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { AlertTriangle, Lock } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { API_BASE_URL } from "@/lib/api-config"
import { useUsersStore } from "@/stores/users-store"

import { userEditFormSchema, type UserEditFormValues } from "./schema"
import type { UserRecord } from "./types"

interface UserEditFormProps {
  user: UserRecord
}

/**
 * Edit User form, per dms-edit-user.html. Username is shown read-only —
 * per the mockup's own footnote, identity fields (username, primary login
 * credential) are Keycloak-managed and this screen only exposes what DMS
 * is permitted to write.
 *
 * KNOWN GAP, more specific than the one on UserInviteForm: the real
 * backend contract here (UpdateUserRequest.java — PUT /api/v1/users/{id})
 * only accepts `displayName` (one combined string, not separate
 * first/last), `mfaEnabled`, and `status`. It has no field for email or
 * for "additional profile attributes" at all — email in particular looks
 * to be Keycloak-managed like username, just not yet marked read-only in
 * this design. Submitting sends `displayName` for real (joining
 * firstName + lastName) and passes through the user's existing
 * mfaEnabled/status unchanged so the required fields are satisfied;
 * `email` and `additionalProfile` are included in the request body for
 * when the backend is extended to accept them, but dms-core ignores them
 * today. The local store update (so this change is visible immediately
 * on the View User screen) applies all four regardless, since it's still
 * mocked data either way.
 */
export function UserEditForm({ user }: UserEditFormProps) {
  const router = useRouter()
  const updateUser = useUsersStore((state) => state.updateUser)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<UserEditFormValues>({
    resolver: zodResolver(userEditFormSchema),
    defaultValues: {
      firstName: user.firstName,
      lastName: user.lastName,
      email: user.email,
      additionalProfile: "",
    },
  })

  async function onSubmit(values: UserEditFormValues) {
    setSubmitError(null)
    const displayName = `${values.firstName} ${values.lastName}`.trim()

    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/users/${user.id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({
        displayName,
        // Passed through unchanged — this form doesn't edit either of
        // these, but UpdateUserRequest requires both. Real status values
        // are active/locked/disabled on the backend; the mock "inactive"
        // here maps to "disabled" as the closest equivalent.
        mfaEnabled: user.mfaStatus.startsWith("Enabled"),
        status: user.status === "active" ? "active" : "disabled",
        // Not yet read by dms-core — see the KNOWN GAP note above.
        email: values.email,
        additionalProfile: values.additionalProfile || undefined,
      }),
    })
    } catch {
      // fetch() throws (rather than resolving with a bad status) when the
      // request never reached a server at all — e.g. the backend isn't
      // running yet. Distinct from a 4xx/5xx below, which means the
      // backend WAS reached and responded.
      setSubmitError("Could not reach the server. Check that the backend is running and try again.")
      return
    }

    if (!response.ok) {
      const body = await response.json().catch(() => null)
      setSubmitError(
        response.status === 403
          ? "You don't have permission to edit this user."
          : (body?.message ?? "Could not save changes.")
      )
      return
    }

    updateUser(user.id, {
      firstName: values.firstName,
      lastName: values.lastName,
      email: values.email,
      additionalProfile: values.additionalProfile,
    })
    router.push(`/admin/users/${user.id}`)
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="rounded-[14px] border border-border bg-card p-[30px] pb-[26px]">
      {submitError && (
        <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
          <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
          {submitError}
        </div>
      )}

      <div className="mb-5 grid grid-cols-2 gap-[18px]">
        <div>
          <Label htmlFor="firstName">First name</Label>
          <Input id="firstName" autoComplete="off" {...register("firstName")} />
          {errors.firstName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.firstName.message}</p>
          )}
        </div>
        <div>
          <Label htmlFor="lastName">Last name</Label>
          <Input id="lastName" autoComplete="off" {...register("lastName")} />
          {errors.lastName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.lastName.message}</p>
          )}
        </div>
      </div>

      <div className="mb-5">
        <Label htmlFor="email">Email</Label>
        <Input id="email" type="email" autoComplete="off" {...register("email")} />
        {errors.email && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.email.message}</p>}
      </div>

      <div className="mb-5">
        <Label className="mb-[7px] flex flex-wrap items-center gap-2 normal-case">
          <span className="text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">
            Username / login ID
          </span>
          <span className="inline-flex items-center gap-1 rounded-full border border-[var(--border-strong)] px-2 py-0.5 text-[10px] font-semibold tracking-[0.04em] text-muted-foreground uppercase">
            <Lock size={10} />
            Read-only — Keycloak-managed identity field
          </span>
        </Label>
        <Input value={user.username} readOnly disabled className="cursor-not-allowed border-dashed" />
      </div>

      <div className="mb-5">
        <Label className="mb-[7px] flex items-center gap-2 normal-case">
          <span className="text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">
            Additional profile attributes
          </span>
          <span className="rounded-full border border-[var(--border-strong)] px-2 py-0.5 text-[10px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
            Optional
          </span>
        </Label>
        <Textarea placeholder="e.g. department, job title, phone" {...register("additionalProfile")} />
        {errors.additionalProfile && (
          <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.additionalProfile.message}</p>
        )}
      </div>

      <hr className="my-5 border-t border-dashed border-border" />

      <div className="flex justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push(`/admin/users/${user.id}`)}
          className="rounded-[9px] border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-[9px] border border-[#123527] bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
        >
          {isSubmitting ? "Saving…" : "Save changes"}
        </button>
      </div>

      <p className="mt-[18px] text-center text-[11.5px] leading-[1.6] text-[var(--ink-faint)]">
        Identity-related fields (username, primary login credential) are governed by Keycloak — this
        screen exposes only fields DMS is permitted to write.
      </p>
    </form>
  )
}