// app/login/page.tsx
import { Suspense } from "react"

import { LoginPageContent } from "./login-page-content"

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginPageContent />
    </Suspense>
  )
}