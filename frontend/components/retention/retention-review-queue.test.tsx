import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { ReviewPageDto } from "@/lib/retention/api"

import { RetentionReviewQueue } from "./retention-review-queue"

let review: ReviewPageDto
let without: ReviewPageDto

vi.mock("@/lib/retention/use-retention", () => ({
  useReviewQueue: (_page: number, _size: number, enabled: boolean) => ({ data: enabled ? review : undefined, isPending: false, isError: false }),
  useWithoutPolicy: (_page: number, _size: number, enabled: boolean) => ({ data: enabled ? without : undefined, isPending: false, isError: false }),
}))

const page = (content: ReviewPageDto["content"]): ReviewPageDto => ({ content, page: 0, size: 25, totalElements: content.length, totalPages: 1 })
const item = {
  documentId: "d1",
  title: "Supplier invoice 1041",
  documentTypeId: "t1",
  documentTypeName: "Invoice",
  folderId: null,
  lifecycleState: "archived" as const,
  policyName: "Invoices",
  framework: "HMRC",
  reviewDueAt: "2033-01-31T09:00:00Z",
  flaggedAt: "2026-11-02T09:00:00Z",
}

describe("RetentionReviewQueue", () => {
  beforeEach(() => {
    review = page([item])
    without = page([{ ...item, documentId: "d2", title: "Loose memo", lifecycleState: "active", policyName: null, framework: null, flaggedAt: null }])
  })

  it("the review queue lists documents past their retention, with their rule - and says nothing was deleted", () => {
    render(<RetentionReviewQueue kind="review" />)
    expect(screen.getByRole("link", { name: "Supplier invoice 1041" })).toHaveAttribute("href", "/documents/d1")
    expect(screen.getByText(/Invoice · Invoices \(HMRC\) · flagged/)).toBeInTheDocument()
    expect(screen.getByText(/Nothing has been deleted/)).toBeInTheDocument()
    expect(screen.queryByText("Loose memo")).not.toBeInTheDocument()
  })

  it("the without-a-rule list shows the documents no rule covers, and what to do about it", () => {
    render(<RetentionReviewQueue kind="without-policy" />)
    expect(screen.getByRole("link", { name: "Loose memo" })).toBeInTheDocument()
    expect(screen.getByText(/Add a rule on the Rules tab/)).toBeInTheDocument()
    expect(screen.queryByText("Supplier invoice 1041")).not.toBeInTheDocument()
  })

  it("an empty queue says so in plain words", () => {
    review = page([])
    const { unmount } = render(<RetentionReviewQueue kind="review" />)
    expect(screen.getByText("No documents are waiting for a retention review.")).toBeInTheDocument()
    unmount()
    without = page([])
    render(<RetentionReviewQueue kind="without-policy" />)
    expect(screen.getByText("Every document is covered by a rule.")).toBeInTheDocument()
  })
})
