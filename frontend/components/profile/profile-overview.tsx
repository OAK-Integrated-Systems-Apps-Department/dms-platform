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

import { formatDateTime } from "@/lib/utils"

import { profileEditFormSchema, type ProfileEditFormValues } from "./schema"

/** Fixed platform role codes from `/me` → display labels; tenant-defined role names are shown as-is. */
const PLATFORM_ROLE_LABELS: Record<string, string> = {
  PLATFORM_ADMIN: "Platform Admin",
  TENANT_ADMIN: "Tenant Admin",
  MEMBER: "Member",
}

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
    formState: { errors, isSubmitting, isValid },
  } = useForm<ProfileEditFormValues>({
    resolver: zodResolver(profileEditFormSchema),
    defaultValues: { firstName: "", lastName: "", position: "" },
    mode: "onChange",
  })

  // Keeps the form's defaults in sync once the real profile has loaded —
  // it isn't available yet on the render where the hook above is
  // initialized.
  useEffect(() => {
    if (user) reset({ firstName: user.firstName, lastName: user.lastName, position: user.position ?? "" })
  }, [user, reset])

  function startEditing() {
    if (user) reset({ firstName: user.firstName, lastName: user.lastName, position: user.position ?? "" })
    setSubmitError(null)
    setIsEditing(true)
  }

  function cancelEditing() {
    if (user) reset({ firstName: user.firstName, lastName: user.lastName, position: user.position ?? "" })
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
    <div className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-6 flex items-center gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-btn bg-primary font-heading text-[18px] font-bold text-primary-foreground">
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
            <div className="mb-5 flex items-start gap-2.5 rounded-btn border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
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
            <div className="col-span-2">
              <Label htmlFor="position">Position (job title)</Label>
              <Input
                id="position"
                autoComplete="off"
                placeholder="e.g. Finance Manager"
                {...register("position")}
              />
              <p className="mt-1 text-[11.5px] text-muted-foreground">
                Shown alongside your name when you place a signature on a document.
              </p>
              {errors.position && (
                <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.position.message}</p>
              )}
            </div>
          </div>
          <div className="mb-6 flex justify-end gap-2.5">
            <button
              type="button"
              onClick={cancelEditing}
              disabled={isSubmitting}
              className="rounded-btn border border-[var(--border-strong)] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70 h-[38px] px-4 text-[13px] inline-flex items-center justify-center"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting || !isValid}
              className="rounded-btn border border-[var(--primary-hover)] bg-primary inline-flex h-[38px] items-center justify-center px-4 text-[13px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
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
          <div>
            <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              Position
            </p>
            <p className="font-mono text-[12.5px] text-foreground">
              {user?.position ?? "—"}
            </p>
          </div>
          <div className="col-span-2 flex items-start justify-end sm:col-span-1">
            <button
              type="button"
              onClick={startEditing}
              disabled={isLoading}
              className="flex items-center gap-1.5 border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70 h-[38px] rounded-btn px-4 text-[13px]"
            >
              <Pencil size={13} />
              Edit
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
            {user?.roles?.length ? (
              user.roles.map((role) => (
                <Badge key={role} variant="outline" className="rounded-btn">
                  {PLATFORM_ROLE_LABELS[role] ?? role}
                </Badge>
              ))
            ) : (
              <p className="font-mono text-[12.5px] text-foreground">{isLoading ? "…" : "—"}</p>
            )}
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
            {user?.lastLoginAt ? formatDateTime(user.lastLoginAt) : isLoading ? "…" : "—"}
          </p>
        </div>
      </div>
    </div>
  )
}
