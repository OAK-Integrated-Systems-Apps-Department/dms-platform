"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, Lock } from "lucide-react"
import { toast } from "sonner"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { fetchUser, syncUserRoles, updateUserProfile } from "@/lib/users/api"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
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
 * `PUT /api/v1/users/{userId}` only accepts `firstName`, `lastName`,
 * `mfaEnabled`, and `status` (see UpdateUserRequest.java) — there's no
 * field for email at all. Email stays on the form because the mockup
 * calls for it, but it's not sent to the backend; it's Keycloak-managed
 * like username, just not yet marked read-only in this design.
 *
 * `mfaEnabled`/`status` are passed through unmodified from a freshly
 * fetched copy of the user (not the `user` prop, which may be a moment
 * stale) — this form has no controls for either, so it must never
 * silently flip one while saving name changes.
 *
 * `roles` is a real form field, submitted via `syncUserRoles`
 * (`lib/users/api.ts`), which diffs the selection against the user's
 * actual current assignments and issues the necessary
 * `POST`/`DELETE /api/v1/users/{userId}/roles` calls — each of which the
 * backend mirrors into Keycloak as a realm role for tenant-wide
 * assignments (see `UserRoleServiceImpl`), not just the local database.
 *
 * This form is reachable by two different single-permission callers who
 * each hold only half of what it does: `otua.manage-users.update` (name/
 * email fields, no role picker) or `otua.manage-users.assign-role` (role
 * picker only, per `canEditProfile` below) — see `UserDetailView`/
 * `UsersTable`'s own doc comments on why the Edit icon/link is shown for
 * either. `onSubmit` only calls `updateUserProfile` (`PUT /users/{id}`,
 * gated on `.update`) when `canEditProfile` is true, so an assign-role-
 * only caller's save never 403s on a field they were never shown.
 */
export function UserEditForm({ user }: UserEditFormProps) {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const roles = useRolesStore((state) => state.roles)
  const rolesLoading = useRolesStore((state) => state.loading)
  const rolesError = useRolesStore((state) => state.error)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  const { hasPermission, hasAnyPermission } = usePermissions()
  const canEditProfile = hasPermission(PermissionCodes.OTUA_USERS_UPDATE)
  // GET /api/v1/roles also accepts otua.manage-users.assign-role on the
  // backend now (see RoleController#listRoles's own comment) — without
  // it, an assign-role-only caller would reach this form and find the
  // picker permanently replaced by the "can't view the role catalog"
  // message below, unable to ever do the one thing their permission is
  // for. asm.manage-roles-permissions.view-role/.update-role still work
  // the same as before for everyone else.
  const canViewRoles = hasAnyPermission(
    PermissionCodes.ASM_ROLES_VIEW_ROLE,
    PermissionCodes.ASM_ROLES_UPDATE_ROLE,
    PermissionCodes.OTUA_USERS_ASSIGN_ROLE
  )
  useEffect(() => {
    // Skip the call entirely for a caller with none of the three
    // permissions above, rather than 403 and, since nothing here marks
    // that permanent otherwise, keep re-firing it on every remount for
    // the rest of the session.
    if (canViewRoles && roles.length === 0 && !rolesError) fetchRoles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canViewRoles])
  // Only active roles can be newly assigned (see the backend guard in
  // UserRoleServiceImpl#assignRole) — filtering here means the picker
  // simply doesn't offer an inactive one for a *new* selection. If this
  // user already holds one that's since gone inactive, it still shows as
  // a removable chip regardless (RoleMultiSelect renders `selected`
  // chips independent of `options` — see that component's own
  // implementation), so there's still a way to unassign it here.
  const ROLE_OPTIONS = roles.filter((role) => role.status === "Active").map((role) => role.name)

  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting, isValid },
  } = useForm<UserEditFormValues>({
    resolver: zodResolver(userEditFormSchema),
    defaultValues: {
      firstName: user.firstName,
      lastName: user.lastName,
      email: user.email,
      roles: user.roles,
    },
    // Live feedback as the caller types past a field's length limit,
    // matching UserInviteForm - see that component's own comment.
    mode: "onChange",
  })

  async function onSubmit(values: UserEditFormValues) {
    setSubmitError(null)

    // An assign-role-only caller (canEditProfile false) never gets a shot
    // at the name/email fields below — they stay fixed at the values this
    // form was loaded with, per userEditFormSchema's defaultValues — so
    // skip the profile PUT entirely rather than resubmit unchanged data
    // through an endpoint this caller doesn't hold otua.manage-users.update
    // for, which would 403 before the role sync below ever ran.
    if (canEditProfile) {
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
        const message = err instanceof Error ? err.message : "Could not save changes."
        setSubmitError(message)
        toast.error(message)
        return
      }
    }

    const { error: roleError } = await syncUserRoles(user.id, values.roles, roles)
    if (roleError) {
      // The profile fields above already saved successfully — say so,
      // rather than implying the whole save failed and inviting a
      // confusing retry.
      const message = `Profile changes saved, but roles could not be fully updated: ${roleError}`
      setSubmitError(message)
      toast.error(message)
      return
    }

    queryClient.invalidateQueries({ queryKey: ["user", user.id] })
    queryClient.invalidateQueries({ queryKey: ["users"] })
    toast.success("User updated.")
    router.push(`/admin/users/${user.id}`)
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="rounded-lg border border-border bg-card p-[30px] pb-[26px]"
    >
      {submitError && (
        <div className="mb-5 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
          <AlertTriangle
            size={16}
            className="mt-px shrink-0 text-[var(--rust)]"
          />
          {submitError}
        </div>
      )}

      {!canEditProfile && (
        <p className="mb-5 rounded-md border border-[var(--border-strong)] bg-muted px-[13px] py-[11px] text-[12.5px] text-muted-foreground">
          You have permission to change this user&apos;s roles, but not their profile
          details below — those fields are read-only for you.
        </p>
      )}

      <div className="mb-5 grid grid-cols-2 gap-[18px]">
        <div>
          <Label htmlFor="firstName">First name</Label>
          <Input
            id="firstName"
            autoComplete="off"
            readOnly={!canEditProfile}
            disabled={!canEditProfile}
            className={!canEditProfile ? "cursor-not-allowed border-dashed" : undefined}
            {...register("firstName")}
          />
          {errors.firstName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">
              {errors.firstName.message}
            </p>
          )}
        </div>
        <div>
          <Label htmlFor="lastName">Last name</Label>
          <Input
            id="lastName"
            autoComplete="off"
            readOnly={!canEditProfile}
            disabled={!canEditProfile}
            className={!canEditProfile ? "cursor-not-allowed border-dashed" : undefined}
            {...register("lastName")}
          />
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
          readOnly={!canEditProfile}
          disabled={!canEditProfile}
          className={!canEditProfile ? "cursor-not-allowed border-dashed" : undefined}
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
            Read-only
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
        {canViewRoles ? (
          <Controller
            name="roles"
            control={control}
            render={({ field }) => (
              <RoleMultiSelect
                options={ROLE_OPTIONS}
                selected={field.value}
                onChange={field.onChange}
                loading={rolesLoading && roles.length === 0}
              />
            )}
          />
        ) : (
          <p className="mt-1.5 text-[12.5px] text-muted-foreground">
            You don&apos;t have permission to view the role catalog, so roles
            can&apos;t be changed here.
          </p>
        )}
        {errors.roles && (
          <p className="mt-1.5 text-[12px] text-[var(--rust)]">
            {errors.roles.message}
          </p>
        )}
      </div>

      <hr className="my-5 border-t border-dashed border-border" />

      <div className="flex justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push(`/admin/users/${user.id}`)}
          className="rounded-btn border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting || !isValid}
          className="rounded-btn border border-[var(--primary-hover)] bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
        >
          {isSubmitting ? "Saving…" : "Save changes"}
        </button>
      </div>
    </form>
  )
}