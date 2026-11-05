import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import type { OrganisationDto, TenantAdminDto } from "@/lib/tenants/admin-api"

const mocks = vi.hoisted(() => ({
  fetchOrganisations: vi.fn(),
  fetchTenantAdmins: vi.fn(),
  provisionOrganisation: vi.fn(),
  updateOrganisation: vi.fn(),
  setOrganisationStatus: vi.fn(),
  deactivateOrganisation: vi.fn(),
  reactivateOrganisation: vi.fn(),
  destroyOrganisationKeys: vi.fn(),
  updateTenantAdmin: vi.fn(),
  resendTenantAdminInvite: vi.fn(),
  fetchOnlineEditingAllowed: vi.fn(),
  setOnlineEditingAllowed: vi.fn(),
}))

vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() } }))
vi.mock("@/lib/tenants/admin-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/tenants/admin-api")>()),
  ...mocks,
}))

import { Organisations } from "./organisations"

function org(overrides: Partial<OrganisationDto> = {}): OrganisationDto {
  return {
    tenantId: "t1",
    tenantName: "Acme Ltd",
    domain: "acme.example.com",
    subscriptionTier: "standard",
    status: "active",
    createdAt: "2026-10-20T10:00:00Z",
    updatedAt: null,
    deactivated: false,
    deactivatedAt: null,
    keysShreddedAt: null,
    ...overrides,
  }
}

function renderScreen(
  orgs: OrganisationDto[] = [org()],
  admins: TenantAdminDto[] = [],
  allowedEditing: string[] = []
) {
  mocks.fetchOnlineEditingAllowed.mockResolvedValue(allowedEditing)
  mocks.fetchOrganisations.mockResolvedValue(orgs)
  mocks.fetchTenantAdmins.mockResolvedValue(admins)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <Organisations />
    </QueryClientProvider>
  )
}

const wrong = () => new ApiError(403, "That password isn't right.", "PASSWORD_INCORRECT")

function passwordBox() {
  return screen.getByLabelText("Your password") as HTMLInputElement
}

describe("Organisations: every create, change and delete asks for the signed-in person's own password", () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
  })

  // ------------------------------------------------------------------ create

  async function fillAddForm() {
    fireEvent.click(await screen.findByRole("button", { name: /Add organisation/ }))
    fireEvent.change(screen.getByLabelText("Organisation name"), { target: { value: "New Co" } })
    fireEvent.change(screen.getByLabelText("Domain"), { target: { value: "newco.example.com" } })
    fireEvent.change(screen.getByLabelText("Plan"), { target: { value: "standard" } })
    fireEvent.change(screen.getAllByLabelText("Email")[0], { target: { value: "boss@newco.example.com" } })
    fireEvent.change(screen.getAllByLabelText("Username")[0], { target: { value: "newco.admin" } })
    fireEvent.change(screen.getAllByLabelText("First name")[0], { target: { value: "Bo" } })
  }

  it("can't create an organisation until the password is entered", async () => {
    renderScreen()
    await fillAddForm()

    const create = screen.getByRole("button", { name: /^Create/ })
    expect(create).toBeDisabled()
    fireEvent.change(passwordBox(), { target: { value: "my-own-password" } })
    expect(create).toBeEnabled()
  })

  it("sends the password with the new organisation - and it is their own, as a password field", async () => {
    renderScreen()
    mocks.provisionOrganisation.mockResolvedValue({
      tenant: org({ tenantName: "New Co" }),
      admin: { userId: "u", email: "boss@newco.example.com", username: "newco.admin" },
      additionalAdmins: [],
    })
    await fillAddForm()
    expect(passwordBox().type).toBe("password")
    expect(passwordBox().autocomplete).toBe("current-password")

    fireEvent.change(passwordBox(), { target: { value: "my-own-password" } })
    fireEvent.click(screen.getByRole("button", { name: /^Create/ }))

    await waitFor(() => expect(mocks.provisionOrganisation).toHaveBeenCalled())
    const [confirmation, request] = mocks.provisionOrganisation.mock.calls[0]
    expect(confirmation).toEqual({ password: "my-own-password", code: undefined })
    expect(request.tenantName).toBe("New Co")
    // The password is for confirming, not part of what is created.
    expect(JSON.stringify(request)).not.toContain("my-own-password")
  })

  it("a wrong password: says so, clears the field to be typed again, and creates nothing", async () => {
    renderScreen()
    mocks.provisionOrganisation.mockRejectedValue(wrong())
    await fillAddForm()
    fireEvent.change(passwordBox(), { target: { value: "oops" } })

    fireEvent.click(screen.getByRole("button", { name: /^Create/ }))

    expect(await screen.findByText("That password isn't right.")).toBeTruthy()
    expect(passwordBox().value).toBe("")
    expect(screen.getByRole("button", { name: /^Create/ })).toBeDisabled()
  })

  it("an account with an authenticator app is asked for the code, and it is sent along", async () => {
    renderScreen()
    mocks.provisionOrganisation
      .mockRejectedValueOnce(new ApiError(403, "Enter the code from your authenticator app as well.", "PASSWORD_OTP_REQUIRED"))
      .mockResolvedValueOnce({ tenant: org(), admin: { userId: "u", email: "e", username: "u" }, additionalAdmins: [] })
    await fillAddForm()
    expect(screen.queryByLabelText("Authenticator code")).toBeNull()
    fireEvent.change(passwordBox(), { target: { value: "my-own-password" } })
    fireEvent.click(screen.getByRole("button", { name: /^Create/ }))

    const code = await screen.findByLabelText("Authenticator code")
    // The password stays; the create button now waits for the code too.
    expect(passwordBox().value).toBe("my-own-password")
    expect(screen.getByRole("button", { name: /^Create/ })).toBeDisabled()
    fireEvent.change(code, { target: { value: "123456" } })
    fireEvent.click(screen.getByRole("button", { name: /^Create/ }))

    await waitFor(() => expect(mocks.provisionOrganisation).toHaveBeenCalledTimes(2))
    expect(mocks.provisionOrganisation.mock.calls[1][0]).toEqual({ password: "my-own-password", code: "123456" })
  })

  // ------------------------------------------------------------------ edit

  it("can't save an edit without the password, and sends it when saving", async () => {
    renderScreen()
    mocks.updateOrganisation.mockResolvedValue(org({ tenantName: "Acme Renamed" }))
    fireEvent.click(await screen.findByRole("button", { name: /Edit/ }))
    fireEvent.change(await screen.findByLabelText("Organisation name"), { target: { value: "Acme Renamed" } })

    const save = screen.getByRole("button", { name: /Save/ })
    expect(save).toBeDisabled()
    fireEvent.change(passwordBox(), { target: { value: "my-own-password" } })
    expect(save).toBeEnabled()
    fireEvent.click(save)

    await waitFor(() => expect(mocks.updateOrganisation).toHaveBeenCalled())
    expect(mocks.updateOrganisation.mock.calls[0][0]).toEqual({ password: "my-own-password", code: undefined })
    expect(mocks.updateOrganisation.mock.calls[0][1].tenantId).toBe("t1")
  })

  // ------------------------------------------------------------------ delete

  it("deactivating asks for the password first: the button waits, and the password goes with the request", async () => {
    renderScreen()
    mocks.deactivateOrganisation.mockResolvedValue(undefined)
    fireEvent.click(await screen.findByRole("button", { name: /Deactivate/ }))
    const dialog = await screen.findByRole("alertdialog")

    const confirmButton = within(dialog).getByRole("button", { name: "Deactivate" })
    expect(confirmButton).toBeDisabled()
    fireEvent.change(within(dialog).getByLabelText("Your password"), { target: { value: "my-own-password" } })
    expect(confirmButton).toBeEnabled()
    fireEvent.click(confirmButton)

    await waitFor(() => expect(mocks.deactivateOrganisation).toHaveBeenCalledWith({ password: "my-own-password", code: undefined }, "t1"))
  })

  it("a wrong password keeps the deactivate dialog open and nothing is deactivated", async () => {
    renderScreen()
    mocks.deactivateOrganisation.mockRejectedValue(wrong())
    fireEvent.click(await screen.findByRole("button", { name: /Deactivate/ }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.change(within(dialog).getByLabelText("Your password"), { target: { value: "oops" } })

    fireEvent.click(within(dialog).getByRole("button", { name: "Deactivate" }))

    await waitFor(() => expect(mocks.deactivateOrganisation).toHaveBeenCalled())
    expect(screen.getByRole("alertdialog")).toBeTruthy()
    expect((within(screen.getByRole("alertdialog")).getByLabelText("Your password") as HTMLInputElement).value).toBe("")
  })

  it("suspending asks for the password too", async () => {
    renderScreen()
    mocks.setOrganisationStatus.mockResolvedValue(org({ status: "suspended" }))
    fireEvent.click(await screen.findByRole("button", { name: /Suspend/ }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.change(within(dialog).getByLabelText("Your password"), { target: { value: "my-own-password" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Suspend" }))

    await waitFor(() => expect(mocks.setOrganisationStatus).toHaveBeenCalled())
    expect(mocks.setOrganisationStatus.mock.calls[0][0]).toEqual({ password: "my-own-password", code: undefined })
    expect(mocks.setOrganisationStatus.mock.calls[0][2]).toBe("suspended")
  })

  it("destroying an organisation's keys - the final delete - asks for the password as well", async () => {
    renderScreen([org({ deactivated: true, deactivatedAt: "2026-10-21T10:00:00Z" })])
    mocks.destroyOrganisationKeys.mockResolvedValue({ wrappedKeysDeleted: 3, unencryptedFilesLeft: 0 })
    fireEvent.click(await screen.findByRole("button", { name: /Destroy keys/ }))
    fireEvent.change(await screen.findByLabelText(/Type the organisation/i), { target: { value: "Acme Ltd" } })
    fireEvent.change(screen.getByLabelText(/Why/i), { target: { value: "Contract ended" } })

    const destroy = screen.getAllByRole("button", { name: /Destroy/ }).at(-1) as HTMLElement
    expect(destroy).toBeDisabled()
    fireEvent.change(passwordBox(), { target: { value: "my-own-password" } })
    expect(destroy).toBeEnabled()
    fireEvent.click(destroy)

    await waitFor(() => expect(mocks.destroyOrganisationKeys).toHaveBeenCalled())
    expect(mocks.destroyOrganisationKeys.mock.calls[0][0]).toEqual({ password: "my-own-password", code: undefined })
  })

  it("bringing a deactivated organisation back needs no password - it only restores", async () => {
    renderScreen([org({ deactivated: true, deactivatedAt: "2026-10-21T10:00:00Z" })])
    mocks.reactivateOrganisation.mockResolvedValue(org())
    fireEvent.click(await screen.findByRole("button", { name: /Reactivate/ }))
    const dialog = await screen.findByRole("alertdialog")

    expect(within(dialog).queryByLabelText("Your password")).toBeNull()
    fireEvent.click(within(dialog).getByRole("button", { name: /Reactivate/ }))
    await waitFor(() => expect(mocks.reactivateOrganisation).toHaveBeenCalledWith("t1"))
  })
})

function admin(overrides: Partial<TenantAdminDto> = {}): TenantAdminDto {
  return {
    userId: "u1",
    username: "ada.admin",
    email: "ada@acme.example.com",
    firstName: "Ada",
    lastName: "Admin",
    displayName: "Ada Admin",
    status: "active",
    verified: true,
    lastLoginAt: null,
    createdAt: "2026-10-20T10:00:00Z",
    ...overrides,
  }
}

const BEA = admin({
  userId: "u2",
  username: "bea.admin",
  email: "bea@acme.example.com",
  firstName: "Bea",
  displayName: "Bea Admin",
})

describe("Organisations: an administrator's username can be changed - but never to one that is taken", () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
  })

  async function openEditorOfAda() {
    renderScreen([org()], [admin(), BEA])
    fireEvent.click(await screen.findByRole("button", { name: /^Edit/ }))
    const sheet = await screen.findByRole("dialog")
    const card = (await within(sheet).findByText("Ada Admin")).closest("div.rounded-lg") as HTMLElement
    fireEvent.click(within(card).getByRole("button", { name: /Edit/ }))
    // The administrator editor is its own group, with its own password to confirm, apart from the organisation's form.
    return within(await within(sheet).findByRole("group", { name: "Edit administrator" }))
  }

  const usernameBox = (sheet: ReturnType<typeof within>) => sheet.getByLabelText("Username") as HTMLInputElement
  const typePassword = (sheet: ReturnType<typeof within>) =>
    fireEvent.change(sheet.getByLabelText("Your password"), { target: { value: "my-own-password" } })

  it("shows the current username in a field that can be edited", async () => {
    const sheet = await openEditorOfAda()

    expect(usernameBox(sheet).value).toBe("ada.admin")
    expect(usernameBox(sheet).disabled).toBe(false)
  })

  it("saves the new username, with the password - and nothing else changed", async () => {
    mocks.updateTenantAdmin.mockResolvedValue(admin({ username: "ada.l" }))
    const sheet = await openEditorOfAda()
    const save = sheet.getByRole("button", { name: "Save administrator" })
    expect(save).toBeDisabled()

    fireEvent.change(usernameBox(sheet), { target: { value: "ada.l" } })
    typePassword(sheet)
    expect(save).toBeEnabled()
    fireEvent.click(save)

    await waitFor(() => expect(mocks.updateTenantAdmin).toHaveBeenCalled())
    const [confirmation, tenantId, userId, changes] = mocks.updateTenantAdmin.mock.calls[0]
    expect(confirmation).toEqual({ password: "my-own-password", code: undefined })
    expect([tenantId, userId]).toEqual(["t1", "u1"])
    expect(changes).toEqual({ firstName: "Ada", lastName: "Admin", email: "ada@acme.example.com", username: "ada.l" })
  })

  it("a change of letter case alone is no change", async () => {
    const sheet = await openEditorOfAda()

    fireEvent.change(usernameBox(sheet), { target: { value: "ADA.ADMIN" } })
    typePassword(sheet)

    expect(sheet.getByRole("button", { name: "Save administrator" })).toBeDisabled()
  })

  it("says at once when another administrator of the organisation has that username, in any letter case, and won't save", async () => {
    const sheet = await openEditorOfAda()

    fireEvent.change(usernameBox(sheet), { target: { value: "BEA.admin" } })
    typePassword(sheet)

    expect(sheet.getByText(/Another administrator here already has this username/)).toBeTruthy()
    expect(sheet.getByRole("button", { name: "Save administrator" })).toBeDisabled()
    expect(mocks.updateTenantAdmin).not.toHaveBeenCalled()
  })

  it("won't take another administrator's email as a username either, since people sign in with either", async () => {
    const sheet = await openEditorOfAda()

    fireEvent.change(usernameBox(sheet), { target: { value: "bea@acme.example.com" } })
    typePassword(sheet)

    expect(sheet.getByRole("button", { name: "Save administrator" })).toBeDisabled()
  })

  it("says at once when the new email is another administrator's", async () => {
    const sheet = await openEditorOfAda()

    fireEvent.change(sheet.getByLabelText("Email"), { target: { value: "Bea@acme.example.com" } })
    typePassword(sheet)

    expect(sheet.getByText("Another administrator here already has this email.")).toBeTruthy()
    expect(sheet.getByRole("button", { name: "Save administrator" })).toBeDisabled()
  })

  it("refuses a username with spaces, odd characters or fewer than 3 characters", async () => {
    const sheet = await openEditorOfAda()
    typePassword(sheet)
    const save = sheet.getByRole("button", { name: "Save administrator" })

    fireEvent.change(usernameBox(sheet), { target: { value: "has space" } })
    expect(sheet.getByText(/Letters, numbers, dots, hyphens and underscores only/)).toBeTruthy()
    expect(save).toBeDisabled()

    fireEvent.change(usernameBox(sheet), { target: { value: "ab" } })
    expect(sheet.getByText("At least 3 characters.")).toBeTruthy()
    expect(save).toBeDisabled()

    fireEvent.change(usernameBox(sheet), { target: { value: "ada.l" } })
    expect(save).toBeEnabled()
  })

  it("shows the server's answer when the username is taken elsewhere, in another organisation say", async () => {
    mocks.updateTenantAdmin.mockRejectedValue(new ApiError(409, "This username is already taken.", "USERNAME_IN_USE"))
    const sheet = await openEditorOfAda()
    fireEvent.change(usernameBox(sheet), { target: { value: "taken.elsewhere" } })
    typePassword(sheet)

    fireEvent.click(sheet.getByRole("button", { name: "Save administrator" }))

    expect(await sheet.findByText("This username is already taken.")).toBeTruthy()
  })
})

describe("Organisations: the platform administrator allows or stops online editing, with their own password", () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
  })

  const toggle = (name: string) => screen.findByRole("switch", { name: `Online editing for ${name}` })

  it("shows, per organisation, whether online editing is allowed", async () => {
    renderScreen([org(), org({ tenantId: "t2", tenantName: "Beta Ltd", domain: "beta.example.com" })], [], ["t2"])

    expect(await toggle("Acme Ltd")).not.toBeChecked()
    expect(await toggle("Beta Ltd")).toBeChecked()
  })

  it("turning it on asks for the password first, and only sends it once that is given", async () => {
    mocks.setOnlineEditingAllowed.mockResolvedValue({ tenantId: "t1", allowed: true })
    renderScreen()
    fireEvent.click(await toggle("Acme Ltd"))
    const dialog = await screen.findByRole("alertdialog")

    expect(within(dialog).getByText(/Allow online editing for Acme Ltd?/)).toBeTruthy()
    const confirmButton = within(dialog).getByRole("button", { name: "Allow" })
    expect(confirmButton).toBeDisabled()
    expect(mocks.setOnlineEditingAllowed).not.toHaveBeenCalled()
    fireEvent.change(within(dialog).getByLabelText("Your password"), { target: { value: "my-own-password" } })
    expect(confirmButton).toBeEnabled()
    fireEvent.click(confirmButton)

    await waitFor(() =>
      expect(mocks.setOnlineEditingAllowed).toHaveBeenCalledWith({ password: "my-own-password", code: undefined }, "t1", true)
    )
  })

  it("stopping it also asks for the password, and says what stopping means", async () => {
    mocks.setOnlineEditingAllowed.mockResolvedValue({ tenantId: "t1", allowed: false })
    renderScreen([org()], [], ["t1"])
    fireEvent.click(await toggle("Acme Ltd"))
    const dialog = await screen.findByRole("alertdialog")

    expect(within(dialog).getByText(/Stop online editing for Acme Ltd?/)).toBeTruthy()
    expect(within(dialog).getByText(/already open can no longer save/)).toBeTruthy()
    fireEvent.change(within(dialog).getByLabelText("Your password"), { target: { value: "my-own-password" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Stop online editing" }))

    await waitFor(() =>
      expect(mocks.setOnlineEditingAllowed).toHaveBeenCalledWith({ password: "my-own-password", code: undefined }, "t1", false)
    )
  })

  it("a wrong password keeps the dialog open, says so, and the switch has not moved", async () => {
    mocks.setOnlineEditingAllowed.mockRejectedValue(wrong())
    renderScreen()
    fireEvent.click(await toggle("Acme Ltd"))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.change(within(dialog).getByLabelText("Your password"), { target: { value: "oops" } })

    fireEvent.click(within(dialog).getByRole("button", { name: "Allow" }))

    await waitFor(() => expect(mocks.setOnlineEditingAllowed).toHaveBeenCalled())
    expect(screen.getByRole("alertdialog")).toBeTruthy()
    expect((within(screen.getByRole("alertdialog")).getByLabelText("Your password") as HTMLInputElement).value).toBe("")
    // The page behind an open dialog is inert, so it is looked up as hidden.
    expect(screen.getByRole("switch", { name: "Online editing for Acme Ltd", hidden: true })).not.toBeChecked()
  })

  it("cancelling changes nothing", async () => {
    renderScreen()
    fireEvent.click(await toggle("Acme Ltd"))
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Go back" }))

    expect(mocks.setOnlineEditingAllowed).not.toHaveBeenCalled()
  })

  it("a deactivated organisation has nothing to switch", async () => {
    renderScreen([org({ deactivated: true, deactivatedAt: "2026-10-21T10:00:00Z" })])

    expect(await toggle("Acme Ltd")).toHaveAttribute("aria-disabled", "true")
  })
})
