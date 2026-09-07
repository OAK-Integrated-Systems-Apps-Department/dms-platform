"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, Lock } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { fetchUser, syncUserRoles, updateUserProfile } from "@/lib/users/api"
import { useRolesStore } from "@/stores/roles-store"

import { RoleMultiSelect } from "./role-multi-select"
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
 * KNOWN GAP: `PUT /api/v1/users/{userId}` only accepts `firstName`,
 * `lastName`, `mfaEnabled`, and `status` (see UpdateUserRequest.java / the
 * endpoint's Swagger page) — there's no field for email or "additional
 * profile attributes" at all. Those two stay on the form because the
 * mockup calls for them, but they're not sent to the backend; email in
 * particular looks to be Keycloak-managed like username, just not yet
 * marked read-only in this design.
 *
 * `mfaEnabled`/`status` are passed through unmodified from a freshly
 * fetched copy of the user (not the `user` prop, which may be a moment
 * stale) — this form has no controls for either, so it must never
 * silently flip one while saving name changes.
 *
 * FIXED KNOWN GAP: roles previously had no editing surface on this screen
 * at all — `user.roles` (from `useUser`/`toUserRecord`) was display-only.
 * `roles` is now a real form field, submitted via `syncUserRoles`
 * (`lib/users/api.ts`), which diffs the selection against the user's
 * actual current assignments and issues the necessary
 * `POST`/`DELETE /api/v1/users/{userId}/roles` calls — each of which the
 * backend mirrors into Keycloak as a realm role for tenant-wide
 * assignments (see `UserRoleServiceImpl`), not just the local database.
 */
export function UserEditForm({ user }: UserEditFormProps) {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const roles = useRolesStore((state) => state.roles)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  useEffect(() => {
    if (roles.length === 0) fetchRoles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const ROLE_OPTIONS = roles.map((role) => role.name)

  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<UserEditFormValues>({
    resolver: zodResolver(userEditFormSchema),
    defaultValues: {
      firstName: user.firstName,
      lastName: user.lastName,
      email: user.email,
      roles: user.roles,
      additionalProfile: "",
    },
  })

  async function onSubmit(values: UserEditFormValues) {
    setSubmitError(null)

    try {
      const current = await fetchUser(user.id)
      await updateUserProfile(user.id, {
        firstName: values.firstName,
        lastName: values.lastName,
        // Not edited on this form — carried over as-is from the current
        // record so saving a name change can't accidentally toggle MFA
        // or flip a locked/disabled account back to active.
        mfaEnabled: current.mfaEnabled,
        status: current.status,
      })
    } catch (err) {
      setSubmitError(
        err instanceof Error ? err.message : "Could not save changes."
      )
      return
    }

    const { error: roleError } = await syncUserRoles(user.id, values.roles, roles)
    if (roleError) {
      // The profile fields above already saved successfully — say so,
      // rather than implying the whole save failed and inviting a
      // confusing retry.
      setSubmitError(`Profile changes saved, but roles could not be fully updated: ${roleError}`)
      return
    }

    queryClient.invalidateQueries({ queryKey: ["user", user.id] })
    queryClient.invalidateQueries({ queryKey: ["users"] })
    router.push(`/admin/users/${user.id}`)
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="rounded-[14px] border border-border bg-card p-[30px] pb-[26px]"
    >
      {submitError && (
        <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
          <AlertTriangle
            size={16}
            className="mt-px shrink-0 text-[var(--rust)]"
          />
          {submitError}
        </div>
      )}

      <div className="mb-5 grid grid-cols-2 gap-[18px]">
        <div>
          <Label htmlFor="firstName">First name</Label>
          <Input id="firstName" autoComplete="off" {...register("firstName")} />
          {errors.firstName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">
              {errors.firstName.message}
            </p>
          )}
        </div>
        <div>
          <Label htmlFor="lastName">Last name</Label>
          <Input id="lastName" autoComplete="off" {...register("lastName")} />
          {errors.lastName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">
              {errors.lastName.message}
            </p>
          )}
        </div>
      </div>

      <div className="mb-5">
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          autoComplete="off"
          {...register("email")}
        />
        {errors.email && (
          <p className="mt-1.5 text-[12px] text-[var(--rust)]">
            {errors.email.message}
          </p>
        )}
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
        <Input
          value={user.username}
          readOnly
          disabled
          className="cursor-not-allowed border-dashed"
        />
      </div>

      <div className="mb-5">
        <Label>Roles</Label>
        <Controller
          name="roles"
          control={control}
          render={({ field }) => (
            <RoleMultiSelect options={ROLE_OPTIONS} selected={field.value} onChange={field.onChange} />
          )}
        />
        {errors.roles && (
          <p className="mt-1.5 text-[12px] text-[var(--rust)]">
            {errors.roles.message}
          </p>
        )}
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
        <Textarea
          placeholder="e.g. department, job title, phone"
          {...register("additionalProfile")}
        />
        {errors.additionalProfile && (
          <p className="mt-1.5 text-[12px] text-[var(--rust)]">
            {errors.additionalProfile.message}
          </p>
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
    </form>
  )
}