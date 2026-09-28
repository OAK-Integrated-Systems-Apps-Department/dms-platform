import "@testing-library/jest-dom/vitest"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"

import { CreateMetadataFieldForm, UpdateMetadataFieldForm } from "./metadata-field-form"
import type { CreateMetadataFieldFormValues, UpdateMetadataFieldFormValues } from "./schema"

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), back: vi.fn() }) }))

const createSubmit = () => vi.fn<(values: CreateMetadataFieldFormValues) => Promise<string | null>>(async () => null)
const updateSubmit = () => vi.fn<(values: UpdateMetadataFieldFormValues) => Promise<string | null>>(async () => null)

const hintsInput = () => screen.getByLabelText(/Label hints/, { selector: "input" })

async function fillRequiredCreateFields(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/Field Key/), "po_number")
  await user.type(screen.getByLabelText(/^Label$/), "PO Number")
}

describe("Label hints on the field create/edit forms", () => {
  it("won't create the field while a refused hint is still in the input", async () => {
    const user = userEvent.setup()
    const onSubmit = createSubmit()
    render(<CreateMetadataFieldForm onSubmit={onSubmit} />)
    await fillRequiredCreateFields(user)
    await user.type(hintsInput(), "PO No{Enter}P")

    await user.click(screen.getByRole("button", { name: "Create Field" }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(/“P” hasn't been added as a hint/)).toBeInTheDocument()
    expect(hintsInput()).toHaveValue("P")
    expect(hintsInput()).toHaveFocus()
  })

  it("refuses a hint past the 20-hint cap instead of dropping it on submit", async () => {
    const user = userEvent.setup()
    const onSubmit = createSubmit()
    render(<CreateMetadataFieldForm onSubmit={onSubmit} />)
    await fillRequiredCreateFields(user)
    await user.click(hintsInput())
    await user.paste(Array.from({ length: 20 }, (_, i) => `Hint ${i}`).join(","))
    await user.type(hintsInput(), "Purchase Order No")

    await user.click(screen.getByRole("button", { name: "Create Field" }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(/“Purchase Order No” hasn't been added as a hint/)).toBeInTheDocument()
  })

  it("submits once the refused text is cleared, and the blocking message goes away", async () => {
    const user = userEvent.setup()
    const onSubmit = createSubmit()
    render(<CreateMetadataFieldForm onSubmit={onSubmit} />)
    await fillRequiredCreateFields(user)
    await user.type(hintsInput(), "PO No{Enter}P")
    await user.click(screen.getByRole("button", { name: "Create Field" }))
    expect(onSubmit).not.toHaveBeenCalled()

    await user.clear(hintsInput())
    expect(screen.queryByText(/hasn't been added as a hint/)).not.toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Create Field" }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ labelAliases: ["PO No"] })
  })

  it("still commits valid typed text on submit without Enter", async () => {
    const user = userEvent.setup()
    const onSubmit = createSubmit()
    render(<CreateMetadataFieldForm onSubmit={onSubmit} />)
    await fillRequiredCreateFields(user)
    await user.type(hintsInput(), "PO Number")
    await user.click(screen.getByRole("button", { name: "Create Field" }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ labelAliases: ["PO Number"] })
  })

  it("won't save the edit form while a refused hint is still in the input", async () => {
    const user = userEvent.setup()
    const onSubmit = updateSubmit()
    render(
      <UpdateMetadataFieldForm
        defaultValues={{ label: "PO Number", helpText: "", defaultValue: "", searchable: true, ocrExtractable: true, labelAliases: ["PO No"] }}
        onSubmit={onSubmit}
      />
    )
    await user.type(hintsInput(), "--")
    await user.click(screen.getByRole("button", { name: "Save Changes" }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(/“--” hasn't been added as a hint/)).toBeInTheDocument()
    expect(screen.getByRole("alert")).toHaveTextContent(/letter or digit/)
  })
})
