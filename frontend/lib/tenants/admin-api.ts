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
