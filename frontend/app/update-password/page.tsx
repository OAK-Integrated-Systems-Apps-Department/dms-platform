// app/update-password/page.tsx
import { Suspense } from "react"

import { UpdatePasswordPageContent } from "./update-password-page-content"

export default function UpdatePasswordPage() {
  return (
    <Suspense fallback={null}>
      <UpdatePasswordPageContent />
    </Suspense>
  )
}
