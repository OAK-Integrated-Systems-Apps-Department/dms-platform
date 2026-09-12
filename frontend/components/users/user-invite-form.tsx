"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, CheckCircle2, ShieldCheck } from "lucide-react"
import { toast } from "sonner"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { API_BASE_URL } from "@/lib/api-config"
import { ApiError } from "@/lib/api-error"
import { apiFetchJson } from "@/lib/api-fetch"
import { assignUserRole } from "@/lib/users/api"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useRolesStore } from "@/stores/roles-store"

import { RoleMultiSelect } from "./role-multi-select"
import { userInviteFormSchema, type UserInviteFormValues } from "./schema"

/**
 * Add User form, per dms-add-user.html. Still an invite, not a signup —
 * no password field: the account is created server-side (dms-core →
 * Keycloak) with a random temporary password, and the new user gets an
 * email with a link to verify their address and set their own password
 * (see app/update-password/page.tsx). Nothing here is reachable without
 * an existing admin session.
 *
 * Role options come from the real tenant role list (`useRolesStore`).
 *
 * `CreateUserRequest` only accepts {email, firstName, lastName} — it has
 * no `roles` field. Role assignment is a genuinely separate API
 * (`POST /api/v1/users/{userId}/roles`, see `UserRoleController`) that
 * needs a `userId`, which doesn't exist until the user itself is created —
 * so this creates the user first, then assigns each selected role as a
 * second step once a real `userId` is available. Each assignment is
 * tenant-scoped and mirrored into Keycloak as a realm role by the backend
 * (see `UserRoleServiceImpl.assignRole`), not just written to the local
 * database.
 *
 * `username` remains unsent — `CreateUserRequest` has no field for it,
 * and there's no separate endpoint for it the way there is for roles.
 */
export function UserInviteForm() {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [invitedEmail, setInvitedEmail] = useState<string | null>(null)

  const roles = useRolesStore((state) => state.roles)
  const rolesError = useRolesStore((state) => state.error)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  const { hasAnyPermission } = usePermissions()
  const canViewRoles = hasAnyPermission(
    PermissionCodes.ASM_ROLES_VIEW_ROLE,
    PermissionCodes.ASM_ROLES_UPDATE_ROLE
  )
  useEffect(() => {
    // GET /api/v1/roles requires asm.manage-roles-permissions.view-role or
    // .update-role — otua.manage-users.* alone (what actually gates
    // reaching this form) doesn't imply it, so skip the call entirely for
    // a caller without either, rather than 403 and, since nothing here
    // marks that permanent otherwise, keep re-firing it on every remount
    // for the rest of the session.
    if (canViewRoles && roles.length === 0 && !rolesError) fetchRoles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canViewRoles])
  // Only active roles can be newly assigned (see the backend guard in
  // UserRoleServiceImpl#assignRole) — filtering here means the picker
  // simply doesn't offer an inactive one, rather than letting someone
  // pick it and then showing a rejection after the fact.
  const ROLE_OPTIONS = roles.filter((role) => role.status === "Active").map((role) => role.name)

  const {
    register,
    handleSubmit,
    control,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<UserInviteFormValues>({
    resolver: zodResolver(userInviteFormSchema),
    defaultValues: { firstName: "", lastName: "", username: "", email: "", roles: [] },
  })

  async function onSubmit(values: UserInviteFormValues) {
    setSubmitError(null)
    let created: { userId: string }
    try {
      created = await apiFetchJson(
        `${API_BASE_URL}/api/v1/users`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            email: values.email,
            firstName: values.firstName,
            lastName: values.lastName,
          }),
        },
        "Could not create the user."
      )
    } catch (err) {
      const message =
        err instanceof ApiError && err.status === 403
          ? "You don't have permission to invite users."
          : err instanceof Error
            ? err.message
            : "Could not create the user."
      setSubmitError(message)
      toast.error(message)
      return
    }

    if (values.roles.length > 0) {
      const roleIdByName = new Map(roles.map((r) => [r.name, r.id]))
      const results = await Promise.allSettled(
        values.roles.map((name) => {
          const roleId = roleIdByName.get(name)
          if (!roleId) {
            return Promise.reject(new Error(`"${name}" isn't in the current role list.`))
          }
          return assignUserRole(created.userId, roleId)
        })
      )
      const failures = results.filter(
        (r): r is PromiseRejectedResult => r.status === "rejected"
      )
      if (failures.length > 0) {
        // The user genuinely exists at this point — don't leave the form
        // implying creation failed. Explain the partial outcome instead,
        // and point at where to finish the job, same tradeoff used when
        // creating a role with permissions pre-selected (see
        // stores/roles-store.ts's addRole).
        const message = `${values.email} was created, but ${failures.length === values.roles.length ? "no roles could" : "some roles could not"} be assigned. Finish assigning roles from their Edit page.`
        setSubmitError(message)
        toast.error(message)
        setInvitedEmail(values.email)
        reset()
        return
      }
    }

    toast.success("User created.")
    setInvitedEmail(values.email)
    reset()
  }

  if (invitedEmail) {
    return (
      <div className="rounded-[14px] border border-primary/30 bg-primary-tint p-6 text-center">
        <CheckCircle2 size={26} className="mx-auto mb-3 text-primary" />
        <p className="mb-4 text-[13.5px] leading-[1.6] text-foreground">
          User created. An email has been sent to <span className="font-semibold">{invitedEmail}</span> to
          verify their address and set a password.
        </p>
        <div className="flex justify-center gap-3">
          <button
            type="button"
            onClick={() => setInvitedEmail(null)}
            className="rounded-[9px] border border-[var(--border-strong)] px-4 py-2 text-[13px] font-semibold text-foreground"
          >
            Add another user
          </button>
          <button
            type="button"
            onClick={() => router.push("/admin/users")}
            className="rounded-[9px] border border-primary bg-primary px-4 py-2 text-[13px] font-semibold text-primary-foreground"
          >
            Back to user list
          </button>
        </div>
      </div>
    )
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
          <Input id="firstName" placeholder="Jane" autoComplete="off" {...register("firstName")} />
          {errors.firstName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.firstName.message}</p>
          )}
        </div>
        <div>
          <Label htmlFor="lastName">Last name</Label>
          <Input id="lastName" placeholder="Perera" autoComplete="off" {...register("lastName")} />
          {errors.lastName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.lastName.message}</p>
          )}
        </div>
      </div>

      <div className="mb-5 grid grid-cols-2 gap-[18px]">
        <div>
          <Label htmlFor="username">Username / login ID</Label>
          <Input id="username" placeholder="jane.perera" autoComplete="off" {...register("username")} />
          {errors.username && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.username.message}</p>
          )}
        </div>
        <div>
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          placeholder="jane.perera@yourcompany.com"
            autoComplete="off"
          {...register("email")}
        />
        {errors.email && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.email.message}</p>}
      </div>
      </div>

      <div className="mb-5">
        <Label>Initial role(s)</Label>
        {canViewRoles ? (
          <Controller
            name="roles"
            control={control}
            render={({ field }) => (
              <RoleMultiSelect options={ROLE_OPTIONS} selected={field.value} onChange={field.onChange} />
            )}
          />
        ) : (
          <p className="mt-1.5 text-[12.5px] text-muted-foreground">
            You don&apos;t have permission to view the role catalog, so roles
            can&apos;t be assigned here. The user can still be invited without
            one.
          </p>
        )}
        {errors.roles && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.roles.message}</p>}
      </div>

      <hr className="my-5 border-t border-dashed border-border" />

      <div className="flex justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push("/admin/users")}
          className="rounded-[9px] border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-[9px] border border-[#123527] bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
        >
          {isSubmitting ? "Creating…" : "Create user"}
        </button>
      </div>
    </form>
  )
}