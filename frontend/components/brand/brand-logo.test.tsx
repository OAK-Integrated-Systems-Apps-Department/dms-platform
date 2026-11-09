import { render } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { BrandLogo } from "./brand-logo"

describe("BrandLogo", () => {
  it("renders both artworks by default, CSS picks one by theme, and only one is announced", () => {
    const { container } = render(<BrandLogo alt="OAK iDocs" className="h-10" />)
    const images = container.querySelectorAll("img")

    expect(images).toHaveLength(2)
    expect(images[0]).toHaveAttribute("src", "/images/logo-light.png")
    expect(images[0]).toHaveClass("dark:hidden", "h-10")
    expect(images[0]).toHaveAttribute("alt", "OAK iDocs")
    expect(images[1]).toHaveAttribute("src", "/images/logo-dark.png")
    expect(images[1]).toHaveClass("hidden", "dark:block", "h-10")
    expect(images[1]).toHaveAttribute("aria-hidden", "true")
    expect(images[1]).toHaveAttribute("alt", "")
  })

  it("is always the dark artwork on a surface that is dark in both themes", () => {
    const { container } = render(<BrandLogo tone="on-dark" alt="OAK iDocs" />)
    const images = container.querySelectorAll("img")

    expect(images).toHaveLength(1)
    expect(images[0]).toHaveAttribute("src", "/images/logo-dark.png")
    expect(images[0]).not.toHaveClass("hidden")
  })

  it("uses the leaf-only mark when asked, in the same two tones", () => {
    const auto = render(
      <BrandLogo mark alt="OAK iDocs" />
    ).container.querySelectorAll("img")
    expect(auto[0]).toHaveAttribute("src", "/images/logo-mark-light.png")
    expect(auto[1]).toHaveAttribute("src", "/images/logo-mark-dark.png")

    const dark = render(
      <BrandLogo mark tone="on-dark" alt="OAK iDocs" />
    ).container.querySelector("img")
    expect(dark).toHaveAttribute("src", "/images/logo-mark-dark.png")
  })
})
