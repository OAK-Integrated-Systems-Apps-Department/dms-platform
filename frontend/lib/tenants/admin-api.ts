import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/tenants`

/** Mirrors TenantResponse, as platform admins see it. */
export interface OrganisationDto {
  tenantId: string
  tenantName: string
  domain: string
  subscriptionTier: string
  /** active | suspended | archived */
  status: string
  createdAt: string
  updatedAt: string | null
  /** removed - can be reactivated, or its keys destroyed */
  deactivated: boolean
  deactivatedAt: string | null
  /** set once its encryption keys were destroyed - final */
  keysShreddedAt: string | null
}

interface Page<T> {
  content: T[]
  totalElements: number
}

/** Every organisation, deactivated ones too. Platform admins only. */
export async function fetchOrganisations(): Promise<OrganisationDto[]> {
  const page = await apiFetchJson<Page<OrganisationDto>>(
    `${BASE}?includeDeactivated=true&size=500&sort=tenantName,asc`,
    undefined,
    "Could not load the organisations."
  )
  return page.content
}

/** What onboarding a new client needs: the organisation and its first administrator. */
export interface ProvisionOrganisationRequest {
  tenantName: string
  /** Lowercase hostname, e.g. "acme.oakdms.com" - it doubles as the login domain. */
  domain: string
  subscriptionTier: string
  adminEmail: string
  adminUsername: string
  adminFirstName: string
  adminLastName?: string
}

export interface ProvisionOrganisationResult {
  tenant: OrganisationDto
  admin: { userId: string; email: string; username: string }
}

/**
 * Onboards a client: creates the organisation AND its first TENANT_ADMIN in
 * one atomic call, and emails them to set a password.
 *
 * Deliberately this rather than `POST /api/v1/tenants`, which makes an
 * organisation nobody can log into - there is no other endpoint that can
 * create a user outside the caller's own tenant or grant TENANT_ADMIN, so a
 * plain create leaves an organisation that has to be finished by hand in
 * Keycloak.
 */
export async function provisionOrganisation(
  request: ProvisionOrganisationRequest
): Promise<ProvisionOrganisationResult> {
  return apiFetchJson(
    `${BASE}/provision`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    },
    "Could not create the organisation."
  )
}

/**
 * Renames an organisation or changes its tier. `status` is sent unchanged -
 * suspending and resuming go through `setOrganisationStatus` instead, so an
 * edit can never alter the lifecycle by accident.
 */
export async function updateOrganisation(
  org: OrganisationDto,
  changes: { tenantName: string; subscriptionTier: string }
): Promise<OrganisationDto> {
  return apiFetchJson(
    `${BASE}/${org.tenantId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tenantName: changes.tenantName,
        subscriptionTier: changes.subscriptionTier,
        status: org.status,
      }),
    },
    "Could not update the organisation."
  )
}

/** Suspend, resume or archive - name and tier are sent back unchanged. */
export async function setOrganisationStatus(
  org: OrganisationDto,
  status: "active" | "suspended" | "archived"
): Promise<OrganisationDto> {
  return apiFetchJson(
    `${BASE}/${org.tenantId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tenantName: org.tenantName,
        subscriptionTier: org.subscriptionTier,
        status,
      }),
    },
    "Could not change the organisation's status."
  )
}

export async function deactivateOrganisation(tenantId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${tenantId}`,
    { method: "DELETE" },
    "Could not deactivate the organisation."
  )
}

export async function reactivateOrganisation(
  tenantId: string
): Promise<OrganisationDto> {
  return apiFetchJson(
    `${BASE}/${tenantId}/reactivate`,
    { method: "POST" },
    "Could not reactivate the organisation."
  )
}

/** Crypto-shredding: destroys its keys - its encrypted documents can never be read again. */
export async function destroyOrganisationKeys(
  tenantId: string,
  confirmTenantName: string,
  reason: string,
  acknowledgeUnencrypted: boolean
): Promise<{ wrappedKeysDeleted: number; unencryptedFilesLeft: number }> {
  return apiFetchJson(
    `${BASE}/${tenantId}/shred-keys`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        confirmTenantName,
        reason,
        acknowledgeUnencrypted,
      }),
    },
    "Could not destroy the organisation's keys."
  )
}
