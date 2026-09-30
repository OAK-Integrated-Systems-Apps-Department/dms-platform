import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { ReviewPageViewer, type Highlight } from "./review-page-viewer"

/**
 * Everything here is in normalized (0..1) page coordinates rendered as
 * percentages. That is the whole trick, and it is what these tests pin: a
 * box placed at x0=0.25 must sit at left:25% whatever the image scaled
 * to, and a drag must produce normalized coordinates rather than pixels.
 */

const HIGHLIGHT: Highlight = { key: "invoice_number", bbox: [0.25, 0.1, 0.75, 0.2], label: "Invoice No" }

function surfaceWithSize(width = 400, height = 800) {
  const surface = screen.getByTestId("page-surface")
  vi.spyOn(surface, "getBoundingClientRect").mockReturnValue({
    left: 0,
    top: 0,
    width,
    height,
    right: width,
    bottom: height,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect)
  // jsdom has no pointer capture.
  surface.setPointerCapture = vi.fn()
  surface.releasePointerCapture = vi.fn()
  return surface
}

describe("ReviewPageViewer", () => {
  it("places a highlight as a percentage of the page, not in pixels", () => {
    render(
      <ReviewPageViewer imageUrl="blob:page" isLoading={false} error={null} highlights={[HIGHLIGHT]} />
    )

    const box = screen.getByTestId("highlight-invoice_number")
    expect(box.style.left).toBe("25%")
    expect(box.style.top).toBe("10%")
    expect(box.style.width).toBe("50%")
    expect(box.style.height).toBe("10%")
  })

  it("reports a clicked highlight by its field key", () => {
    const onHighlightClick = vi.fn()
    render(
      <ReviewPageViewer
        imageUrl="blob:page"
        isLoading={false}
        error={null}
        highlights={[HIGHLIGHT]}
        onHighlightClick={onHighlightClick}
      />
    )

    fireEvent.click(screen.getByTestId("highlight-invoice_number"))

    expect(onHighlightClick).toHaveBeenCalledWith("invoice_number")
  })

  it("turns a drag into normalized coordinates", () => {
    const onRegionSelected = vi.fn()
    render(
      <ReviewPageViewer
        imageUrl="blob:page"
        isLoading={false}
        error={null}
        highlights={[]}
        selecting
        onRegionSelected={onRegionSelected}
      />
    )
    const surface = surfaceWithSize(400, 800)

    fireEvent.pointerDown(surface, { clientX: 100, clientY: 200, pointerId: 1 })
    fireEvent.pointerMove(surface, { clientX: 300, clientY: 600, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })

    expect(onRegionSelected).toHaveBeenCalledWith([0.25, 0.25, 0.75, 0.75])
  })

  it("normalizes a drag made upward and to the left", () => {
    const onRegionSelected = vi.fn()
    render(
      <ReviewPageViewer
        imageUrl="blob:page"
        isLoading={false}
        error={null}
        highlights={[]}
        selecting
        onRegionSelected={onRegionSelected}
      />
    )
    const surface = surfaceWithSize(400, 800)

    fireEvent.pointerDown(surface, { clientX: 300, clientY: 600, pointerId: 1 })
    fireEvent.pointerMove(surface, { clientX: 100, clientY: 200, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })

    // x0 < x1 and y0 < y1 regardless of which way the user dragged --
    // the backend rejects an inverted box.
    expect(onRegionSelected).toHaveBeenCalledWith([0.25, 0.25, 0.75, 0.75])
  })

  it("clamps a drag that leaves the page", () => {
    const onRegionSelected = vi.fn()
    render(
      <ReviewPageViewer
        imageUrl="blob:page"
        isLoading={false}
        error={null}
        highlights={[]}
        selecting
        onRegionSelected={onRegionSelected}
      />
    )
    const surface = surfaceWithSize(400, 800)

    fireEvent.pointerDown(surface, { clientX: 100, clientY: 200, pointerId: 1 })
    fireEvent.pointerMove(surface, { clientX: 900, clientY: 2000, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })

    // A few pixels of overshoot must not cost the reviewer the whole
    // selection with a 422 they did not earn.
    expect(onRegionSelected).toHaveBeenCalledWith([0.25, 0.25, 1, 1])
  })

  it("treats a click as a cancel, not a zero-area selection", () => {
    const onRegionSelected = vi.fn()
    const onCancelSelection = vi.fn()
    render(
      <ReviewPageViewer
        imageUrl="blob:page"
        isLoading={false}
        error={null}
        highlights={[]}
        selecting
        onRegionSelected={onRegionSelected}
        onCancelSelection={onCancelSelection}
      />
    )
    const surface = surfaceWithSize(400, 800)

    fireEvent.pointerDown(surface, { clientX: 100, clientY: 200, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })

    expect(onRegionSelected).not.toHaveBeenCalled()
    expect(onCancelSelection).toHaveBeenCalled()
  })

  it("does not select a region when it is not in selecting mode", () => {
    const onRegionSelected = vi.fn()
    render(
      <ReviewPageViewer
        imageUrl="blob:page"
        isLoading={false}
        error={null}
        highlights={[]}
        onRegionSelected={onRegionSelected}
      />
    )
    const surface = surfaceWithSize()

    fireEvent.pointerDown(surface, { clientX: 100, clientY: 200, pointerId: 1 })
    fireEvent.pointerMove(surface, { clientX: 300, clientY: 600, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })

    expect(onRegionSelected).not.toHaveBeenCalled()
  })

  it("shows the reason it cannot render a page rather than an empty frame", () => {
    render(
      <ReviewPageViewer
        imageUrl={null}
        isLoading={false}
        error={new Error("That document version no longer exists.")}
        highlights={[]}
      />
    )

    expect(screen.getByText("That document version no longer exists.")).toBeInTheDocument()
  })
})
