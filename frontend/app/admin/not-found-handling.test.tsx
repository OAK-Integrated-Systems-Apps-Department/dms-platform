import { fireEvent, render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { USER_VIEW_PERMISSIONS } from "@/lib/auth/user-view-permissions"
import { QUARANTINE_ANY_PERMISSION } from "@/lib/quarantine/permissions"

// FE-I1: the eight pages that route a missing resource to the not-found screen must decide it from the HTTP status (404),
// not from the words in the error message - and must do so without disturbing what FE-M4 made them do about permissions.
// The real PermissionGate and the real usePermissions run; only the data sources (current user, the page's own query) are controlled.

type CurrentUserState = { data?: { permissions: string[] }; isLoading: boolean; isError: boolean; refetch: () => void }
type QueryState = { data: unknown; isPending: boolean; isError: boolean; error: unknown }

const mocks = vi.hoisted(() => ({
  currentUser: { data: undefined, isLoading: false, isError: false, refetch: () => {} } as unknown,
  query: { data: undefined, isPending: false, isError: false, error: null } as unknown,
  queryCalls: vi.fn(),
  refetch: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("next/navigation", () => ({
  useParams: () => ({ userId: "u1", id: "x1" }),
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND")
  },
}))
vi.mock("sonner", () => ({ toast: { error: (...args: unknown[]) => mocks.toastError(...args) } }))
vi.mock("@/lib/use-page-title", () => ({ usePageTitle: () => {} }))
vi.mock("@/lib/auth/use-current-user", () => ({ useCurrentUser: () => mocks.currentUser }))
const result = () => ({
  ...(mocks.query as QueryState),
  toggleStatus: vi.fn(),
  sendPasswordReset: vi.fn(),
  release: vi.fn(),
  overrideRelease: vi.fn(),
  remove: vi.fn(),
  isActing: false,
})
vi.mock("@/lib/users/use-user", () => ({ useUser: (id: string) => (mocks.queryCalls("user", id), result()) }))
vi.mock("@/lib/audit-events/use-audit-events", () => ({ useAuditEvent: (id: string) => (mocks.queryCalls("audit", id), result()) }))
vi.mock("@/lib/quarantine/use-quarantine-record", () => ({ useQuarantineRecord: (id: string) => (mocks.queryCalls("quarantine", id), result()) }))
vi.mock("@/stores/users-store", () => ({ useUsersStore: (selector: (s: unknown) => unknown) => selector({ revokeSession: vi.fn(), revokeAllSessions: vi.fn() }) }))
vi.mock("@/components/layout/dashboard-shell", () => ({ DashboardShell: ({ children }: { children: ReactNode }) => <div data-testid="shell">{children}</div> }))
const Content = (name: string) => {
  function ProtectedContent() {
    return <div data-testid="protected-content">{name}</div>
  }
  return ProtectedContent
}
vi.mock("@/components/users/user-detail-view", () => ({ UserDetailView: Content("detail") }))
vi.mock("@/components/users/user-edit-form", () => ({ UserEditForm: Content("edit") }))
vi.mock("@/components/departments/user-department-select", () => ({ UserDepartmentSelect: () => null }))
vi.mock("@/components/users/user-audit-history", () => ({ UserAuditHistory: Content("activity") }))
vi.mock("@/components/users/user-sessions", () => ({ UserSessions: Content("sessions") }))
vi.mock("@/components/users/user-mfa-management", () => ({ UserMfaManagement: Content("mfa") }))
vi.mock("@/components/users/access-summary", () => ({ UserAccessSummary: Content("access-summary") }))
vi.mock("@/components/quarantine/quarantine-action-dialog", () => ({ QuarantineActionDialog: () => null }))

const USER = { id: "u1", name: "Pat Example" }
const DENIED = "You don't have permission to view this page."
const PLACEHOLDER = "Nothing to show right now."

type Page = {
  name: string
  load: () => Promise<{ default: () => ReactNode }>
  /** Permissions that let the person in. */
  granted: string[]
  /** Whether the page's own gate sits in front of its query (FE-M4's four subpages) or the query runs first (the other four). */
  gated: boolean
  data: unknown
}
const PAGES: Page[] = [
  { name: "user detail", load: () => import("./users/[userId]/page"), granted: [PermissionCodes.OTUA_USERS_VIEW], gated: false, data: USER },
  { name: "user edit", load: () => import("./users/[userId]/edit/page"), granted: [PermissionCodes.OTUA_USERS_UPDATE], gated: false, data: USER },
  { name: "user activity", load: () => import("./users/[userId]/activity/page"), granted: [...USER_VIEW_PERMISSIONS], gated: true, data: USER },
  { name: "user sessions", load: () => import("./users/[userId]/sessions/page"), granted: [...USER_VIEW_PERMISSIONS], gated: true, data: USER },
  { name: "user MFA", load: () => import("./users/[userId]/mfa/page"), granted: [...USER_VIEW_PERMISSIONS], gated: true, data: USER },
  { name: "user access summary", load: () => import("./users/[userId]/access-summary/page"), granted: [...USER_VIEW_PERMISSIONS], gated: true, data: USER },
  { name: "audit event", load: () => import("./audit-log/[id]/page"), granted: [PermissionCodes.ABDR_VIEW_AUDIT_HISTORY], gated: false, data: undefined },
  { name: "quarantined file", load: () => import("./quarantine/[id]/page"), granted: [...QUARANTINE_ANY_PERMISSION], gated: false, data: undefined },
]

function loadedUser(permissions: string[]): CurrentUserState {
  return { data: { permissions }, isLoading: false, isError: false, refetch: mocks.refetch }
}
const queryState = (state: Partial<QueryState>, page: Page) => {
  mocks.query = { data: page.data, isPending: false, isError: false, error: null, ...state }
}
const failed = (error: unknown, page: Page) => queryState({ data: undefined, isError: true, error }, page)

async function renderPage(page: Page) {
  const { default: Component } = await page.load()
  return render(<Component />)
}
/** Renders and returns whether the page called notFound() (which throws in this test). */
async function callsNotFound(page: Page) {
  const spy = vi.spyOn(console, "error").mockImplementation(() => {})
  try {
    await renderPage(page)
    return false
  } catch (error) {
    if (error instanceof Error && error.message === "NEXT_NOT_FOUND") return true
    throw error
  } finally {
    spy.mockRestore()
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.currentUser = loadedUser([])
})
afterEach(() => vi.restoreAllMocks())

describe.each(PAGES)("$name page - what counts as 'not found'", (page) => {
  beforeEach(() => {
    mocks.currentUser = loadedUser(page.granted)
  })

  it("a genuine 404 (dms-core's own wording) shows the not-found screen", async () => {
    failed(new ApiError(404, "Thing not found: 123"), page)
    expect(await callsNotFound(page)).toBe(true)
  })

  it.each(["There is nothing at this address.", "Gone", "", "Could not load this."])("a 404 worded %j still shows the not-found screen", async (message) => {
    failed(new ApiError(404, message), page)
    expect(await callsNotFound(page)).toBe(true)
  })

  it("a 500 whose message says 'not found' does NOT show the not-found screen - it shows the page's load failure", async () => {
    failed(new ApiError(500, "Thing not found: 123 (internal lookup failed)"), page)

    expect(await callsNotFound(page)).toBe(false)

    expect(screen.getByText(PLACEHOLDER)).toBeInTheDocument()
    expect(mocks.toastError).toHaveBeenCalledWith("Thing not found: 123 (internal lookup failed)", expect.anything())
  })

  it("a 403 does not show the not-found screen, even when its message says 'not found'", async () => {
    failed(new ApiError(403, "Resource not found or not allowed"), page)

    expect(await callsNotFound(page)).toBe(false)

    expect(screen.getByText(PLACEHOLDER)).toBeInTheDocument()
    expect(mocks.toastError).toHaveBeenCalledWith("You don't have permission to do that.", expect.anything())
  })

  it("a plain 403 keeps the existing load failure", async () => {
    failed(new ApiError(403, "Access denied"), page)

    expect(await callsNotFound(page)).toBe(false)
    expect(screen.getByText(PLACEHOLDER)).toBeInTheDocument()
  })

  it("a network failure keeps the existing load failure", async () => {
    failed(new ApiError(0, "Could not reach the server. Check your connection and try again."), page)

    expect(await callsNotFound(page)).toBe(false)
    expect(screen.getByText(PLACEHOLDER)).toBeInTheDocument()
    expect(mocks.toastError).toHaveBeenCalledWith("Could not reach the server. Check your connection and try again.", expect.anything())
  })

  it("a plain Error worded 'not found' is not a 404 - only a status can say so", async () => {
    failed(new Error("Thing not found: 123"), page)

    expect(await callsNotFound(page)).toBe(false)
    expect(screen.getByText(PLACEHOLDER)).toBeInTheDocument()
  })

  it("a generic failure keeps the existing load failure", async () => {
    failed(new Error("Boom"), page)

    expect(await callsNotFound(page)).toBe(false)
    expect(screen.getByText(PLACEHOLDER)).toBeInTheDocument()
  })

  it("while loading, shows the loading state: no not-found screen, no failure", async () => {
    queryState({ data: undefined, isPending: true }, page)

    expect(await callsNotFound(page)).toBe(false)
    expect(screen.getByRole("status")).toBeInTheDocument()
    expect(screen.queryByText(PLACEHOLDER)).not.toBeInTheDocument()
  })
})

describe.each(PAGES)("$name page - permissions are unchanged", (page) => {
  it("someone with no permission is denied, with no protected content", async () => {
    mocks.currentUser = loadedUser([])
    queryState({}, page)

    expect(await callsNotFound(page)).toBe(false)

    expect(screen.getByText(DENIED)).toBeInTheDocument()
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
  })

  it("someone allowed sees the page - no denial, no not-found screen", async () => {
    mocks.currentUser = loadedUser(page.granted)
    queryState({}, page)

    expect(await callsNotFound(page)).toBe(false)

    expect(screen.queryByText(DENIED)).not.toBeInTheDocument()
    expect(screen.getByTestId("shell")).toBeInTheDocument()
  })

  it("while the permissions load, shows the loading state, not a denial", async () => {
    mocks.currentUser = { data: undefined, isLoading: true, isError: false, refetch: mocks.refetch }
    queryState({}, page)

    expect(await callsNotFound(page)).toBe(false)

    expect(screen.queryByText(DENIED)).not.toBeInTheDocument()
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
  })

  it("when the permissions could not be loaded, offers 'try again' (which retries) and no denial", async () => {
    mocks.currentUser = { data: undefined, isLoading: false, isError: true, refetch: mocks.refetch }
    queryState({}, page)

    expect(await callsNotFound(page)).toBe(false)

    expect(screen.queryByText(DENIED)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: /try again/i }))
    expect(mocks.refetch).toHaveBeenCalledTimes(1)
  })
})

// FE-M4: the four user subpages ask for nothing until the gate has approved - so a missing user cannot be routed to
// not-found (or anything else) for someone who is denied, still being checked, or being asked to retry.
describe.each(PAGES.filter((p) => p.gated))("$name page - FE-M4: nothing is requested, and nothing is decided, before the gate approves", (page) => {
  it("a denied person never reaches the query or the not-found screen, even if the user would be a 404", async () => {
    mocks.currentUser = loadedUser(["docm.documents.view", PermissionCodes.OTUA_USERS_CREATE])
    failed(new ApiError(404, "User not found: u1"), page)

    expect(await callsNotFound(page)).toBe(false)

    expect(mocks.queryCalls).not.toHaveBeenCalled()
    expect(screen.getByText(DENIED)).toBeInTheDocument()
  })

  it("while the permissions load, nothing is requested and the not-found screen is not reached", async () => {
    mocks.currentUser = { data: undefined, isLoading: true, isError: false, refetch: mocks.refetch }
    failed(new ApiError(404, "User not found: u1"), page)

    expect(await callsNotFound(page)).toBe(false)

    expect(mocks.queryCalls).not.toHaveBeenCalled()
  })

  it("when the permissions could not be loaded, nothing is requested and the not-found screen is not reached", async () => {
    mocks.currentUser = { data: undefined, isLoading: false, isError: true, refetch: mocks.refetch }
    failed(new ApiError(404, "User not found: u1"), page)

    expect(await callsNotFound(page)).toBe(false)

    expect(mocks.queryCalls).not.toHaveBeenCalled()
  })

  it("once approved, the user is requested for the page's id, and a genuine 404 then reaches not-found", async () => {
    mocks.currentUser = loadedUser([PermissionCodes.OTUA_USERS_VIEW])
    failed(new ApiError(404, "User not found: u1"), page)

    expect(await callsNotFound(page)).toBe(true)

    expect(mocks.queryCalls).toHaveBeenCalledWith("user", "u1")
  })

  it.each(USER_VIEW_PERMISSIONS)("%s alone is enough to reach the 404 decision", async (code) => {
    mocks.currentUser = loadedUser([code])
    failed(new ApiError(404, "User not found: u1"), page)

    expect(await callsNotFound(page)).toBe(true)
  })
})
