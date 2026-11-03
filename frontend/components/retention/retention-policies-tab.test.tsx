import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RetentionPolicyDto } from "@/lib/retention/api"

import { RetentionPoliciesTab, toRequest } from "./retention-policies-tab"

let rules: RetentionPolicyDto[] = []
let granted: string[] = []
const create = vi.fn()
const update = vi.fn()
const remove = vi.fn()

vi.mock("@/lib/retention/use-retention", () => ({
  useRetentionPolicies: () => ({ data: rules, isPending: false, isError: false }),
  useRetentionScopes: () => ({
    data: {
      documentTypes: [{ id: "t1", name: "Invoice", parentId: null }],
      departments: [{ id: "d1", name: "Finance", parentId: null }],
      folders: [
        { id: "f1", name: "Contracts", parentId: null },
        { id: "f2", name: "Signed", parentId: "f1" },
      ],
    },
  }),
  usePolicyActions: () => ({
    create: { mutateAsync: create, isPending: false },
    update: { mutateAsync: update, isPending: false },
    remove: { mutateAsync: remove, isPending: false },
  }),
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

const CREATE = "crm.manage-retention-policies.create"
const UPDATE = "crm.manage-retention-policies.update"
const DELETE = "crm.manage-retention-policies.delete"

const rule: RetentionPolicyDto = {
  policyId: "p1",
  name: "Invoices",
  description: null,
  scopeType: "document_type",
  scopeId: "t1",
  scopeName: "Invoice",
  includeSubfolders: true,
  activeAmount: 1,
  activeUnit: "years",
  archiveAmount: 6,
  archiveUnit: "years",
  clockStart: "created",
  framework: "HMRC",
  enabled: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: null,
  maxVersions: 5,
  versionAgeAmount: 2,
  versionAgeUnit: "years",
}

const base = {
  name: "Default",
  description: "",
  scopeType: "organisation" as const,
  scopeId: "",
  includeSubfolders: true,
  activeAmount: "1",
  activeUnit: "years" as const,
  archiveAmount: "6",
  archiveUnit: "years" as const,
  clockStart: "created" as const,
  framework: "",
  enabled: true,
  maxVersions: "",
  versionAgeAmount: "",
  versionAgeUnit: "years" as const,
}

describe("toRequest", () => {
  it("turns the form into exactly what the backend takes", () => {
    expect(toRequest({ ...base, framework: " HMRC " })).toEqual({
      request: {
        name: "Default",
        description: null,
        scopeType: "organisation",
        scopeId: null,
        includeSubfolders: true,
        activeAmount: 1,
        activeUnit: "years",
        archiveAmount: 6,
        archiveUnit: "years",
        clockStart: "created",
        framework: "HMRC",
        enabled: true,
        maxVersions: null,
        versionAgeAmount: null,
        versionAgeUnit: null,
      },
    })
  })

  it("version limits are optional: blank is no limit, and the age unit only travels with an age", () => {
    expect(toRequest({ ...base, maxVersions: "5", versionAgeAmount: "2", versionAgeUnit: "months" })).toMatchObject({
      request: { maxVersions: 5, versionAgeAmount: 2, versionAgeUnit: "months" },
    })
    expect(toRequest({ ...base, versionAgeUnit: "months" })).toMatchObject({
      request: { maxVersions: null, versionAgeAmount: null, versionAgeUnit: null },
    })
  })

  it("refuses version limits that are not whole numbers in range", () => {
    expect(toRequest({ ...base, maxVersions: "0" })).toMatchObject({ problem: expect.stringContaining("1 and 10000") })
    expect(toRequest({ ...base, maxVersions: "2.5" })).toMatchObject({ problem: expect.stringContaining("1 and 10000") })
    expect(toRequest({ ...base, versionAgeAmount: "0" })).toMatchObject({ problem: expect.stringContaining("Remove old versions") })
    expect(toRequest({ ...base, versionAgeAmount: "1.5" })).toMatchObject({ problem: expect.stringContaining("Remove old versions") })
  })

  it("no archive period is zero, and a scope needs its target", () => {
    expect(toRequest({ ...base, archiveAmount: "" })).toMatchObject({ request: { archiveAmount: 0 } })
    expect(toRequest({ ...base, scopeType: "folder" })).toEqual({ problem: "Choose which folder it covers." })
    expect(toRequest({ ...base, scopeType: "department", scopeId: "d1" })).toMatchObject({ request: { scopeId: "d1" } })
  })

  it("explains the first thing wrong instead of sending it", () => {
    expect(toRequest({ ...base, name: "  " })).toEqual({ problem: "Give the rule a name." })
    expect(toRequest({ ...base, activeAmount: "0" })).toMatchObject({ problem: expect.stringContaining("active period") })
    expect(toRequest({ ...base, activeAmount: "1.5" })).toMatchObject({ problem: expect.stringContaining("active period") })
    expect(toRequest({ ...base, archiveAmount: "-1" })).toMatchObject({ problem: expect.stringContaining("archive period") })
  })
})

describe("RetentionPoliciesTab", () => {
  beforeEach(() => {
    rules = [rule]
    granted = []
    create.mockReset().mockResolvedValue(undefined)
    update.mockReset().mockResolvedValue(undefined)
    remove.mockReset().mockResolvedValue(undefined)
  })

  it("lists each rule with its scope, periods, clock and regulation", () => {
    render(<RetentionPoliciesTab />)
    expect(screen.getByText("Invoices")).toBeInTheDocument()
    expect(screen.getByText("HMRC")).toBeInTheDocument()
    expect(screen.getByText(/Document type: Invoice · 1 year active, then 6 years archived · counted from creation/)).toBeInTheDocument()
  })

  it("says what a rule does to old versions", () => {
    render(<RetentionPoliciesTab />)
    expect(screen.getByText("Old versions: keeps at most 5 versions, removes old versions after 2 years")).toBeInTheDocument()
  })

  it("the version limits can only be set by someone holding the version-retention permissions", () => {
    granted = [CREATE]
    rules = []
    const { unmount } = render(<RetentionPoliciesTab />)
    fireEvent.click(screen.getByRole("button", { name: /New rule/ }))
    expect(screen.getByLabelText("Keep at most this many versions of a document")).toBeDisabled()
    expect(screen.getByLabelText("Remove superseded versions older than")).toBeDisabled()
    expect(screen.getByText(/needs the version-retention permissions/)).toBeInTheDocument()
    unmount()

    granted = [CREATE, "dvc.configure-version-retention-rules.set-max-versions", "dvc.configure-version-retention-rules.set-retention-period"]
    render(<RetentionPoliciesTab />)
    fireEvent.click(screen.getByRole("button", { name: /New rule/ }))
    expect(screen.getByLabelText("Keep at most this many versions of a document")).toBeEnabled()
    expect(screen.getByLabelText("Remove superseded versions older than")).toBeEnabled()
  })

  it("offers New, Edit and Delete only with their own permissions", () => {
    const { unmount } = render(<RetentionPoliciesTab />)
    expect(screen.queryByRole("button", { name: /New rule/ })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Edit Invoices" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Delete Invoices" })).not.toBeInTheDocument()
    unmount()
    granted = [CREATE, UPDATE, DELETE]
    render(<RetentionPoliciesTab />)
    expect(screen.getByRole("button", { name: /New rule/ })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Edit Invoices" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Delete Invoices" })).toBeInTheDocument()
  })

  it("says nothing happens until a rule exists", () => {
    rules = []
    render(<RetentionPoliciesTab />)
    expect(screen.getByText(/nothing is archived and no document is flagged/)).toBeInTheDocument()
  })

  it("creating a rule sends the chosen scope, periods and label", async () => {
    granted = [CREATE]
    rules = []
    render(<RetentionPoliciesTab />)
    fireEvent.click(screen.getByRole("button", { name: /New rule/ }))

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Contracts" } })
    fireEvent.change(screen.getByLabelText("Applies to"), { target: { value: "folder" } })
    // Folders are offered with their path.
    expect(await screen.findByRole("option", { name: "Contracts / Signed" })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Folder"), { target: { value: "f2" } })
    fireEvent.change(screen.getByLabelText("Active for"), { target: { value: "30" } })
    fireEvent.change(screen.getByLabelText("Active period unit"), { target: { value: "days" } })
    fireEvent.change(screen.getByLabelText("Regulation or policy (a label)"), { target: { value: "Companies Act 2006" } })
    fireEvent.click(screen.getByRole("button", { name: "Create rule" }))

    await vi.waitFor(() =>
      expect(create).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Contracts",
          scopeType: "folder",
          scopeId: "f2",
          activeAmount: 30,
          activeUnit: "days",
          framework: "Companies Act 2006",
        })
      )
    )
  })

  it("a form with a problem is not sent, and says what is wrong", () => {
    granted = [CREATE]
    rules = []
    render(<RetentionPoliciesTab />)
    fireEvent.click(screen.getByRole("button", { name: /New rule/ }))
    fireEvent.click(screen.getByRole("button", { name: "Create rule" }))
    expect(screen.getByRole("alert")).toHaveTextContent("Give the rule a name.")
    expect(create).not.toHaveBeenCalled()
  })
})
