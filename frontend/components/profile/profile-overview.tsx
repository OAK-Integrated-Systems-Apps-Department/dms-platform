"use client"

import { useEffect, useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { AlertTriangle, Pencil } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { useTenant } from "@/lib/tenants/use-tenant"
import { updateCurrentUserProfile } from "@/lib/users/api"

import { profileSupplement } from "./data"
import { profileEditFormSchema, type ProfileEditFormValues } from "./schema"

/**
 * My Profile (S1) — end-user, read-mostly view of their own account:
 * name, login/email, tenant, role(s), and account/security info. Unlike
 * the admin User Detail screen, this reads the self-service
 * `/api/v1/users/me` (`useCurrentUser`) rather than the admin API — a
 * regular user's token has no access to the admin endpoints used
 * elsewhere in Role/User Management.
 *
 * First/last name are the only editable fields here (`isEditing` below),
 * saved via `PUT /api/v1/users/me` (`updateCurrentUserProfile`) — the same
 * react-hook-form + zod pattern `UserEditForm` uses for the admin Edit User
 * form, just inline in this card instead of a separate route, since this
 * page has no other edit surface to reuse. Email, tenant, role(s), account
 * created, and last login stay read-only — none of them are user-editable
 * (identity/tenant/role changes are admin-only, per `UpdateUserRequest`
 * vs. `UpdateOwnProfileRequest` on the backend).
 *
 * Corner radius is fixed at 10px across every surface here (card, avatar,
 * badges, button), matching Role Details and the Permission Matrix rather
 * than the app-wide 9px/14px mix used elsewhere.
 */
export function ProfileOverview() {
  const queryClient = useQueryClient()
  const { data: user, initials, isLoading } = useCurrentUser()
  // Resolves tenantId -> tenantName; GET /api/v1/tenants/{tenantId} allows
  // any user in that tenant, not just admins, so this works for a regular
  // end-user viewing their own profile.
  const { data: tenant, isLoading: isTenantLoading } = useTenant(user?.tenantId)

  const [isEditing, setIsEditing] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ProfileEditFormValues>({
    resolver: zodResolver(profileEditFormSchema),
    defaultValues: { firstName: "", lastName: "" },
  })

  // Keeps the form's defaults in sync once the real profile has loaded —
  // it isn't available yet on the render where the hook above is
  // initialized.
  useEffect(() => {
    if (user) reset({ firstName: user.firstName, lastName: user.lastName })
  }, [user, reset])

  function startEditing() {
    if (user) reset({ firstName: user.firstName, lastName: user.lastName })
    setSubmitError(null)
    setIsEditing(true)
  }

  function cancelEditing() {
    if (user) reset({ firstName: user.firstName, lastName: user.lastName })
    setSubmitError(null)
    setIsEditing(false)
  }

  async function onSubmit(values: ProfileEditFormValues) {
    setSubmitError(null)
    try {
      await updateCurrentUserProfile(values)
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not save changes."
      setSubmitError(message)
      toast.error(message)
      return
    }
    queryClient.invalidateQueries({ queryKey: ["current-user"] })
    toast.success("Profile updated.")
    setIsEditing(false)
  }

  return (
    <div className="rounded-md border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-6 flex items-center gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-md bg-primary font-heading text-[18px] font-bold text-primary-foreground">
          {initials ?? "…"}
        </span>
        <div>
          <p className="mb-0.5 font-heading text-[17px] font-semibold">
            {isLoading ? "Loading…" : (user?.displayName ?? "—")}
          </p>
          <p className="font-mono text-[12px] text-muted-foreground">
            {user?.email ?? "—"}
          </p>
        </div>
      </div>

      {isEditing ? (
        <form onSubmit={handleSubmit(onSubmit)}>
          {submitError && (
            <div className="mb-5 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
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
          <div className="mb-6 flex justify-end gap-2.5">
            <button
              type="button"
              onClick={cancelEditing}
              disabled={isSubmitting}
              className="rounded-btn border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="rounded-btn border border-[var(--primary-hover)] bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
            >
              {isSubmitting ? "Saving…" : "Save changes"}
            </button>
          </div>
        </form>
      ) : (
        <div className="mb-6 grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-4">
          <div>
            <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              First Name
            </p>
            <p className="font-mono text-[12.5px] text-foreground">
              {user?.firstName ?? "—"}
            </p>
          </div>
          <div>
            <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              Last Name
            </p>
            <p className="font-mono text-[12.5px] text-foreground">
              {user?.lastName ?? "—"}
            </p>
          </div>
          <div className="col-span-2 flex items-start justify-end sm:col-span-2">
            <button
              type="button"
              onClick={startEditing}
              disabled={isLoading}
              className="flex items-center gap-1.5 rounded-md border border-[var(--border-strong)] bg-card px-4 py-2.5 text-[13px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70"
            >
              <Pencil size={13} />
              Edit name
            </button>
          </div>
        </div>
      )}

      {/* Key/value grid */}
      <div className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-4">
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Tenant
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {tenant?.tenantName ?? (isLoading || isTenantLoading ? "…" : "—")}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Role(s)
          </p>
          <div className="flex flex-wrap gap-1.5">
            {profileSupplement.roles.map((role) => (
              <Badge key={role} variant="outline" className="rounded-md">
                {role}
              </Badge>
            ))}
          </div>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Account Created
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {user?.createdAt ? user.createdAt.slice(0, 10) : "—"}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Last Login
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {profileSupplement.lastLogin}
          </p>
        </div>
      </div>
    </div>
  )
}
