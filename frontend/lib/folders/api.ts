import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/folders`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** Mirrors FolderResponse - one folder of the shared tree. */
export interface FolderDto {
  folderId: string
  /** null = a top-level folder. */
  parentFolderId: string | null
  name: string
  documentCount: number
  createdAt: string
  updatedAt: string | null
  /** The folder's own access setting. */
  accessMode: FolderAccessMode
  /** Restricted, by this folder or one it inherits from. */
  restricted: boolean
  /** What the caller may do here. */
  myAccess: FolderAccessLevel
}

export type FolderAccessMode = "inherit" | "everyone" | "restricted"
/** view: browse and download; edit: also upload, move documents, create and rename subfolders; manage: also move/delete the folder and change its access. */
export type FolderAccessLevel = "none" | "view" | "edit" | "manage"
export type PrincipalType = "user" | "role"

const LEVEL_ORDER: FolderAccessLevel[] = ["none", "view", "edit", "manage"]

/** Whether `level` allows at least `needed`. */
export function hasAccess(
  level: FolderAccessLevel | undefined,
  needed: FolderAccessLevel
) {
  return LEVEL_ORDER.indexOf(level ?? "manage") >= LEVEL_ORDER.indexOf(needed)
}

/** Mirrors FolderAccessEntryResponse. */
export interface FolderAccessEntryDto {
  principalType: PrincipalType
  principalId: string
  name: string
  /** A user's email, or "Role". */
  detail: string | null
  level: Exclude<FolderAccessLevel, "none">
}

/** Mirrors FolderAccessResponse - who can get into a folder. */
export interface FolderAccessDto {
  folderId: string
  mode: FolderAccessMode
  effectiveMode: "everyone" | "restricted"
  /** Set when the folder inherits: the folder whose setting applies. */
  inheritedFromFolderId: string | null
  inheritedFromName: string | null
  entries: FolderAccessEntryDto[]
  myAccess: FolderAccessLevel
  canManage: boolean
}

/** Mirrors AccessPrincipal - a user or role the picker can add. */
export interface AccessPrincipalDto {
  principalType: PrincipalType
  principalId: string
  name: string
  detail: string | null
}

export async function fetchFolderAccess(
  folderId: string
): Promise<FolderAccessDto> {
  return apiFetchJson(
    `${BASE}/${folderId}/access`,
    undefined,
    "Could not load who can access this folder."
  )
}

export async function saveFolderAccess(
  folderId: string,
  mode: FolderAccessMode,
  entries: Pick<
    FolderAccessEntryDto,
    "principalType" | "principalId" | "level"
  >[]
): Promise<FolderAccessDto> {
  return apiFetchJson(
    `${BASE}/${folderId}/access`,
    {
      method: "PUT",
      headers: JSON_HEADERS,
      body: JSON.stringify({ mode, entries }),
    },
    "Could not save who can access this folder."
  )
}

export async function searchAccessPrincipals(
  q: string
): Promise<AccessPrincipalDto[]> {
  return apiFetchJson(
    `${BASE}/access/principals?q=${encodeURIComponent(q)}`,
    undefined,
    "Could not search people and roles."
  )
}

export interface FolderPathItemDto {
  folderId: string
  name: string
}

/** Every folder of the tenant, flat - build the tree with {@link buildFolderTree}. */
export async function fetchFolders(): Promise<FolderDto[]> {
  return apiFetchJson(BASE, undefined, "Could not load folders.")
}

/** Restricted folders shared with the caller by name or role (not ones they created). */
export async function fetchSharedFolders(): Promise<FolderDto[]> {
  return apiFetchJson(
    `${BASE}/shared-with-me`,
    undefined,
    "Could not load shared folders."
  )
}

export async function createFolder(
  name: string,
  parentFolderId: string | null
): Promise<FolderDto> {
  return apiFetchJson(
    BASE,
    {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ name, parentFolderId }),
    },
    "Could not create the folder."
  )
}

export async function renameFolder(
  folderId: string,
  name: string
): Promise<FolderDto> {
  return apiFetchJson(
    `${BASE}/${folderId}`,
    { method: "PATCH", headers: JSON_HEADERS, body: JSON.stringify({ name }) },
    "Could not rename the folder."
  )
}

export async function moveFolder(
  folderId: string,
  parentFolderId: string | null
): Promise<FolderDto> {
  return apiFetchJson(
    `${BASE}/${folderId}/move`,
    {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ parentFolderId }),
    },
    "Could not move the folder."
  )
}

export async function deleteFolder(folderId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${folderId}`,
    { method: "DELETE" },
    "Could not delete the folder."
  )
}

export interface FolderNode extends FolderDto {
  children: FolderNode[]
  /** "Finance / Invoices / 2026" */
  path: string
  depth: number
}

/** Nests the flat list (children sorted by name) and works out each folder's path. */
export function buildFolderTree(folders: FolderDto[]): {
  roots: FolderNode[]
  byId: Map<string, FolderNode>
} {
  const byId = new Map<string, FolderNode>()
  for (const folder of folders) {
    byId.set(folder.folderId, {
      ...folder,
      children: [],
      path: folder.name,
      depth: 0,
    })
  }
  const roots: FolderNode[] = []
  for (const node of byId.values()) {
    const parent = node.parentFolderId
      ? byId.get(node.parentFolderId)
      : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  const sort = (nodes: FolderNode[]) =>
    nodes.sort((a, b) => a.name.localeCompare(b.name))
  const walk = (nodes: FolderNode[], prefix: string, depth: number) => {
    sort(nodes)
    for (const node of nodes) {
      node.path = prefix ? `${prefix} / ${node.name}` : node.name
      node.depth = depth
      walk(node.children, node.path, depth + 1)
    }
  }
  walk(roots, "", 0)
  return { roots, byId }
}

/** Every folder in tree order, for pickers ("Finance / Invoices"). */
export function flattenFolderTree(roots: FolderNode[]): FolderNode[] {
  const out: FolderNode[] = []
  const walk = (nodes: FolderNode[]) => {
    for (const node of nodes) {
      out.push(node)
      walk(node.children)
    }
  }
  walk(roots)
  return out
}

/** The folder and everything below it - folders it can't be moved into. */
export function subtreeIds(node: FolderNode): Set<string> {
  const ids = new Set<string>()
  const walk = (n: FolderNode) => {
    ids.add(n.folderId)
    n.children.forEach(walk)
  }
  walk(node)
  return ids
}
