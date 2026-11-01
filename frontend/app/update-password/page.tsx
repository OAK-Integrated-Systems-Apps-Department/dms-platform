// app/update-password/page.tsx
import type { Metadata } from "next"
import { Suspense } from "react"

import { UpdatePasswordPageContent } from "./update-password-page-content"

export const metadata: Metadata = { title: "Set your password" }

export default function UpdatePasswordPage() {
  return (
    <Suspense fallback={null}>
      <UpdatePasswordPageContent />
    </Suspense>
  )
}
