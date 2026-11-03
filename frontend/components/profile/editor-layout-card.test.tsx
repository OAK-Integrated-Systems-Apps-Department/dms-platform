import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { UserPreferencesDto } from "@/lib/checkout/api"

import { EditorLayoutCard } from "./editor-layout-card"

let prefs: UserPreferencesDto | undefined
const mutate = vi.fn()

vi.mock("@/lib/checkout/use-checkout", () => ({
  useUserPreferences: () => ({ data: prefs }),
  useSaveUserPreferences: () => ({ mutate, isPending: false }),
}))

describe("EditorLayoutCard", () => {
  beforeEach(() => {
    mutate.mockClear()
    prefs = undefined
  })

  it("shows inside the DMS page as chosen until the user has chosen something else", () => {
    render(<EditorLayoutCard />)
    expect(screen.getByRole("radio", { name: /Inside the DMS page/ })).toHaveAttribute("aria-checked", "true")
    expect(screen.getByRole("radio", { name: /Full page/ })).toHaveAttribute("aria-checked", "false")
  })

  it("shows the saved choice", () => {
    prefs = { editorLayout: "fullpage" }
    render(<EditorLayoutCard />)
    expect(screen.getByRole("radio", { name: /Full page/ })).toHaveAttribute("aria-checked", "true")
  })

  it("choosing the other layout saves exactly that; choosing the current one saves nothing", () => {
    render(<EditorLayoutCard />)
    fireEvent.click(screen.getByRole("radio", { name: /Inside the DMS page/ }))
    expect(mutate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole("radio", { name: /Full page/ }))
    expect(mutate).toHaveBeenCalledWith({ editorLayout: "fullpage" })
  })
})
