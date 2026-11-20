import { fireEvent, render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { USER_VIEW_PERMISSIONS } from "@/lib/auth/user-view-permissions"

type CurrentUserState = { data?: { permissions: string[] }; isLoading: boolean; isError: boolean; refetch: () => void }

const mocks = vi.hoisted(() => ({
  currentUser: { data: undefined, isLoading: false, isError: false, refetch: () => {} } as unknown,
  useUser: vi.fn(),
  refetch: vi.fn(),
  revokeSession: vi.fn(),
  revokeAllSessions: vi.fn(),
}))

vi.mock("next/navigation", () => ({
  useParams: () => ({ userId: "u1" }),
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND")
  },
}))
vi.mock("sonner", () => ({ toast: { error: vi.fn() } }))
vi.mock("@/lib/use-page-title", () => ({ usePageTitle: () => {} }))
// The real PermissionGate and the real usePermissions run; only their data source (the current user) is controlled.
vi.mock("@/lib/auth/use-current-user", () => ({ useCurrentUser: () => mocks.currentUser }))
vi.mock("@/lib/users/use-user", () => ({ useUser: (id: string) => mocks.useUser(id) }))
vi.mock("@/stores/users-store", () => ({
  useUsersStore: (selector: (s: unknown) => unknown) =>
    selector({ revokeSession: mocks.revokeSession, revokeAllSessions: mocks.revokeAllSessions }),
}))
vi.mock("@/components/layout/dashboard-shell", () => ({
  DashboardShell: ({ children }: { children: ReactNode }) => <div data-testid="shell">{children}</div>,
}))
function ProtectedContent({ name, props }: { name: string; props: Record<string, unknown> }) {
  // The props that matter to the tests travel in the DOM, so nothing outside the component is modified while rendering.
  return (
    <div data-testid="protected-content" data-can-manage={String(props.canManage)}>
      {name}: {(props.user as { name: string }).name}
    </div>
  )
}
vi.mock("@/components/users/user-audit-history", () => ({ UserAuditHistory: (p: Record<string, unknown>) => <ProtectedContent name="activity" props={p} /> }))
vi.mock("@/components/users/user-sessions", () => ({ UserSessions: (p: Record<string, unknown>) => <ProtectedContent name="sessions" props={p} /> }))
vi.mock("@/components/users/user-mfa-management", () => ({ UserMfaManagement: (p: Record<string, unknown>) => <ProtectedContent name="mfa" props={p} /> }))
vi.mock("@/components/users/access-summary", () => ({ UserAccessSummary: (p: Record<string, unknown>) => <ProtectedContent name="access-summary" props={p} /> }))

const USER = { id: "u1", name: "Pat Example" }

function loaded(permissions: string[]): CurrentUserState {
  return { data: { permissions }, isLoading: false, isError: false, refetch: mocks.refetch }
}

const PAGES = [
  { name: "activity", crumb: "Activity", load: () => import("./activity/page") },
  { name: "sessions", crumb: "Sessions", load: () => import("./sessions/page") },
  { name: "mfa", crumb: "MFA", load: () => import("./mfa/page") },
  { name: "access-summary", crumb: "Access Summary", load: () => import("./access-summary/page") },
] as const

async function renderPage(page: (typeof PAGES)[number]) {
  const { default: Page } = await page.load()
  return render(<Page />)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.currentUser = loaded([])
  mocks.useUser.mockReturnValue({ data: USER, isPending: false, isError: false, error: null, toggleStatus: vi.fn() })
})

describe.each(PAGES)("user $name page - permission gate", (page) => {
  it("denies someone with none of the accepted permissions, inside the shell, with no protected content", async () => {
    mocks.currentUser = loaded(["docm.documents.view", PermissionCodes.OTUA_USERS_CREATE, "something.else"])

    await renderPage(page)

    expect(screen.getByText("You don't have permission to view this page.")).toBeInTheDocument()
    expect(screen.getByTestId("shell")).toContainElement(screen.getByText("You don't have permission to view this page."))
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
    expect(screen.queryByText("Pat Example", { exact: false })).not.toBeInTheDocument()
  })

  it("denies someone with no permissions at all", async () => {
    mocks.currentUser = loaded([])

    await renderPage(page)

    expect(screen.getByText("You don't have permission to view this page.")).toBeInTheDocument()
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
  })

  it("does not ask for the user at all while denied (no protected request before authorisation)", async () => {
    mocks.currentUser = loaded([])

    await renderPage(page)

    expect(mocks.useUser).not.toHaveBeenCalled()
  })

  it.each(USER_VIEW_PERMISSIONS)("lets someone holding only %s in", async (code) => {
    mocks.currentUser = loaded([code])

    await renderPage(page)

    expect(screen.getByTestId("protected-content")).toHaveTextContent("Pat Example")
    expect(screen.queryByText("You don't have permission to view this page.")).not.toBeInTheDocument()
    expect(mocks.useUser).toHaveBeenCalledWith("u1")
  })

  it("keeps the shell, the breadcrumb and the heading once allowed", async () => {
    mocks.currentUser = loaded([PermissionCodes.OTUA_USERS_VIEW])

    await renderPage(page)

    expect(screen.getByTestId("shell")).toContainElement(screen.getByTestId("protected-content"))
    expect(screen.getByRole("link", { name: "Users" })).toHaveAttribute("href", "/admin/users")
    expect(screen.getByRole("link", { name: "Pat Example" })).toHaveAttribute("href", "/admin/users/u1")
    expect(screen.getByText(page.crumb, { selector: '[aria-current="page"]' })).toBeInTheDocument()
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument()
  })

  it("shows the loading state, not a denial, while permissions load - and asks for nothing yet", async () => {
    mocks.currentUser = { data: undefined, isLoading: true, isError: false, refetch: mocks.refetch }

    await renderPage(page)

    expect(screen.getByRole("status")).toBeInTheDocument()
    expect(screen.queryByText("You don't have permission to view this page.")).not.toBeInTheDocument()
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
    expect(mocks.useUser).not.toHaveBeenCalled()
  })

  it("shows the retry state, not a denial, when the permissions could not be loaded", async () => {
    mocks.currentUser = { data: undefined, isLoading: false, isError: true, refetch: mocks.refetch }

    await renderPage(page)

    expect(screen.queryByText("You don't have permission to view this page.")).not.toBeInTheDocument()
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: /try again/i }))
    expect(mocks.refetch).toHaveBeenCalledTimes(1)
    expect(mocks.useUser).not.toHaveBeenCalled()
  })

  it("still shows the page's own loading state for the user once allowed", async () => {
    mocks.currentUser = loaded([PermissionCodes.OTUA_USERS_VIEW])
    mocks.useUser.mockReturnValue({ data: undefined, isPending: true, isError: false, error: null, toggleStatus: vi.fn() })

    await renderPage(page)

    expect(screen.getByText("Loading user…")).toBeInTheDocument()
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
  })

  it("still shows a load failure for the user once allowed (the backend's own 403 included)", async () => {
    mocks.currentUser = loaded([PermissionCodes.OTUA_USERS_VIEW])
    mocks.useUser.mockReturnValue({ data: undefined, isPending: false, isError: true, error: new Error("Boom"), toggleStatus: vi.fn() })

    await renderPage(page)

    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument()
    expect(screen.queryByText("You don't have permission to view this page.")).not.toBeInTheDocument()
  })
})

describe("user subpages - the page gate does not widen action-level permissions", () => {
  async function canManageFor(pageName: string, permissions: string[]) {
    mocks.currentUser = loaded(permissions)
    const { unmount } = await renderPage(PAGES.find((p) => p.name === pageName)!)
    const canManage = screen.getByTestId("protected-content").getAttribute("data-can-manage") === "true"
    unmount() // a test may check several pages in a row
    return canManage
  }

  it.each(USER_VIEW_PERMISSIONS)("sessions: %s lets you in, and revoking needs update", async (code) => {
    expect(await canManageFor("sessions", [code])).toBe(code === PermissionCodes.OTUA_USERS_UPDATE)
  })

  it.each(USER_VIEW_PERMISSIONS)("mfa: %s lets you in, and the admin actions need update", async (code) => {
    expect(await canManageFor("mfa", [code])).toBe(code === PermissionCodes.OTUA_USERS_UPDATE)
  })

  it.each(USER_VIEW_PERMISSIONS)("access summary: %s lets you in, and activate/deactivate needs one of those two", async (code) => {
    const expected = code === PermissionCodes.OTUA_USERS_ACTIVATE || code === PermissionCodes.OTUA_USERS_DEACTIVATE
    expect(await canManageFor("access-summary", [code])).toBe(expected)
  })

  it("view-only access sees every page read-only", async () => {
    for (const name of ["sessions", "mfa", "access-summary"]) {
      expect(await canManageFor(name, [PermissionCodes.OTUA_USERS_VIEW])).toBe(false)
    }
  })

  it("the actions are still enabled for the people who held them before", async () => {
    expect(await canManageFor("sessions", [PermissionCodes.OTUA_USERS_UPDATE])).toBe(true)
    expect(await canManageFor("mfa", [PermissionCodes.OTUA_USERS_UPDATE])).toBe(true)
    expect(await canManageFor("access-summary", [PermissionCodes.OTUA_USERS_DEACTIVATE])).toBe(true)
  })
})