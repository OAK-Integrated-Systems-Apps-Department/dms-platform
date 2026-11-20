import { act, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { NavProgress } from "./nav-progress"

const mocks = vi.hoisted(() => ({ pathname: "/documents" }))
vi.mock("next/navigation", () => ({ usePathname: () => mocks.pathname }))

let consoleError: ReturnType<typeof vi.spyOn>
const bar = () => screen.getByRole("progressbar", { hidden: true })
const fill = () => bar().firstElementChild as HTMLElement
const isVisible = () => bar().getAttribute("aria-hidden") === "false"
const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms) })

/** An anchor like Next's <Link>: it prevents the default navigation, but only after the capture-phase listener has run. */
function addLink(href: string, attrs: Record<string, string> = {}) {
  const a = document.createElement("a")
  a.setAttribute("href", href)
  for (const [k, v] of Object.entries(attrs)) a.setAttribute(k, v)
  a.addEventListener("click", (e) => e.preventDefault())
  document.body.appendChild(a)
  return a
}

function click(el: Element, init: MouseEventInit = {}) {
  act(() => { el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, button: 0, ...init })) })
}

beforeEach(() => {
  vi.useFakeTimers()
  mocks.pathname = "/documents"
  consoleError = vi.spyOn(console, "error").mockImplementation(() => {})
})

afterEach(() => {
  document.body.innerHTML = ""
  consoleError.mockRestore()
  vi.useRealTimers()
})

describe("NavProgress - starting", () => {
  it("is hidden and empty until something is clicked", () => {
    render(<NavProgress />)

    expect(isVisible()).toBe(false)
    expect(fill().style.width).toBe("0%")
  })

  it("a click on an internal link to another page starts the bar", () => {
    render(<NavProgress />)

    click(addLink("/tasks"))

    expect(isVisible()).toBe(true)
    expect(fill().style.width).toBe("8%")
  })

  it("then eases towards 90% and never reaches it", () => {
    render(<NavProgress />)
    click(addLink("/tasks"))

    advance(200)
    const first = parseFloat(fill().style.width)
    advance(10_000)
    const later = parseFloat(fill().style.width)

    expect(first).toBeGreaterThan(8)
    expect(later).toBeGreaterThan(first)
    expect(later).toBeLessThan(90)
  })

  it.each([
    // The component compares with window.location, which is "/" in the test browser.
    ["a link to the page already shown", "/"],
    ["an external link", "https://example.com/x"],
    ["a hash link", "#section"],
    ["an empty href", ""],
  ])("does not start for %s", (_name, href) => {
    render(<NavProgress />)

    click(addLink(href))

    expect(isVisible()).toBe(false)
  })

  it("does not start for a new-tab link, a download, a modified click or a non-primary button", () => {
    render(<NavProgress />)

    click(addLink("/tasks", { target: "_blank" }))
    click(addLink("/tasks", { download: "" }))
    click(addLink("/tasks"), { metaKey: true })
    click(addLink("/tasks"), { ctrlKey: true })
    click(addLink("/tasks"), { shiftKey: true })
    click(addLink("/tasks"), { altKey: true })
    click(addLink("/tasks"), { button: 1 })

    expect(isVisible()).toBe(false)
  })

  it("does not start for a click that is not on a link", () => {
    render(<NavProgress />)
    const div = document.createElement("div")
    document.body.appendChild(div)

    click(div)

    expect(isVisible()).toBe(false)
  })

  it("a second click while already running does not start a second set of timers", () => {
    render(<NavProgress />)
    click(addLink("/tasks"))
    const timersWhileRunning = vi.getTimerCount()

    click(addLink("/inbox"))

    expect(vi.getTimerCount()).toBe(timersWhileRunning)
  })
})

describe("NavProgress - finishing", () => {
  it("a route change completes the bar, then hides and resets it", () => {
    const { rerender } = render(<NavProgress />)
    click(addLink("/tasks"))

    mocks.pathname = "/tasks"
    rerender(<NavProgress />)

    expect(fill().style.width).toBe("100%")
    advance(259)
    expect(isVisible()).toBe(true)
    advance(2)
    expect(isVisible()).toBe(false)
    expect(fill().style.width).toBe("0%")
  })

  it("stops easing once finished", () => {
    const { rerender } = render(<NavProgress />)
    click(addLink("/tasks"))
    mocks.pathname = "/tasks"
    rerender(<NavProgress />)

    advance(5_000)

    expect(fill().style.width).toBe("0%")
    expect(vi.getTimerCount()).toBe(0)
  })

  it("a route change with nothing running does nothing", () => {
    const { rerender } = render(<NavProgress />)

    mocks.pathname = "/tasks"
    rerender(<NavProgress />)

    expect(isVisible()).toBe(false)
    expect(fill().style.width).toBe("0%")
  })

  it("gives up after 20 seconds if the route never changes (the safety net)", () => {
    render(<NavProgress />)
    click(addLink("/slow"))

    advance(19_999)
    expect(isVisible()).toBe(true)
    advance(2)
    expect(fill().style.width).toBe("100%")
    advance(300)
    expect(isVisible()).toBe(false)
  })

  it("can start again after finishing", () => {
    const { rerender } = render(<NavProgress />)
    click(addLink("/tasks"))
    mocks.pathname = "/tasks"
    rerender(<NavProgress />)
    advance(300)
    expect(isVisible()).toBe(false)

    click(addLink("/inbox"))

    expect(isVisible()).toBe(true)
    expect(fill().style.width).toBe("8%")
  })

  it("a new click during the hide delay cancels the pending hide", () => {
    const { rerender } = render(<NavProgress />)
    click(addLink("/tasks"))
    mocks.pathname = "/tasks"
    rerender(<NavProgress />)
    advance(100)

    click(addLink("/inbox"))
    advance(500)

    expect(isVisible()).toBe(true)
  })
})

describe("NavProgress - cleanup", () => {
  it("unmounting while running clears every timer, so nothing fires afterwards", () => {
    const { unmount } = render(<NavProgress />)
    click(addLink("/tasks"))
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    unmount()

    expect(vi.getTimerCount()).toBe(0)
    advance(30_000)
    expect(consoleError).not.toHaveBeenCalled()
  })

  it("unmounting during the hide delay clears that timer too", () => {
    const { rerender, unmount } = render(<NavProgress />)
    click(addLink("/tasks"))
    mocks.pathname = "/tasks"
    rerender(<NavProgress />)
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    unmount()

    expect(vi.getTimerCount()).toBe(0)
    advance(1_000)
    expect(consoleError).not.toHaveBeenCalled()
  })

  it("unmounting removes the click listener: a later click starts nothing and warns about nothing", () => {
    const { unmount } = render(<NavProgress />)
    unmount()

    click(addLink("/tasks"))
    advance(1_000)

    expect(vi.getTimerCount()).toBe(0)
    expect(consoleError).not.toHaveBeenCalled()
  })

  it("remounting works like a fresh start", () => {
    const first = render(<NavProgress />)
    click(addLink("/tasks"))
    first.unmount()

    render(<NavProgress />)

    expect(isVisible()).toBe(false)
    click(addLink("/inbox"))
    expect(isVisible()).toBe(true)
  })
})