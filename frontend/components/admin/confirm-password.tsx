"use client"

import { useId, useState } from "react"
import { ShieldCheck } from "lucide-react"

import { Input } from "@/components/ui/input"
import { ApiError } from "@/lib/api-error"
import {
  PASSWORD_INCORRECT,
  PASSWORD_OTP_REQUIRED,
  type PasswordConfirmation,
} from "@/lib/auth/password-confirmation"

export interface PasswordConfirmationState {
  password: string
  setPassword: (value: string) => void
  code: string
  setCode: (value: string) => void
  /** The account has an authenticator app, so the code is needed too. */
  needsCode: boolean
  /** Something to send: a password, and a code when one is needed. */
  ready: boolean
  /** What to send with the request. */
  value: PasswordConfirmation
  /** Call with a failed request's error: asks for the code when the server wants it, clears a wrong password. */
  noteError: (error: unknown) => void
}

/**
 * The state behind {@link ConfirmPasswordFields}: the person's own password to confirm a sensitive change. The
 * server checks it every time, so a wrong one is cleared (to be typed again) and a request that is refused for want
 * of an authenticator code makes the code field appear.
 */
export function usePasswordConfirmation(): PasswordConfirmationState {
  const [password, setPassword] = useState("")
  const [code, setCode] = useState("")
  const [needsCode, setNeedsCode] = useState(false)
  return {
    password,
    setPassword,
    code,
    setCode,
    needsCode,
    ready: password !== "" && (!needsCode || code.trim() !== ""),
    value: { password, code: needsCode ? code : undefined },
    noteError(error) {
      if (!(error instanceof ApiError)) return
      if (error.code === PASSWORD_OTP_REQUIRED) setNeedsCode(true)
      if (error.code === PASSWORD_INCORRECT) {
        setPassword("")
        setCode("")
      }
    },
  }
}

/**
 * "Confirm with your password": the field a sensitive change ends with. The password is the signed-in person's own,
 * not the one of whoever is being edited.
 */
export function ConfirmPasswordFields({
  confirm,
  label = "Your password",
  hint = "Enter your own password to confirm this change.",
}: {
  confirm: PasswordConfirmationState
  label?: string
  hint?: string
}) {
  const passwordId = useId()
  const codeId = useId()
  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border bg-muted/30 p-3.5">
      <div>
        <label
          htmlFor={passwordId}
          className="mb-1 flex items-center gap-1.5 text-[12px] font-medium text-foreground"
        >
          <ShieldCheck size={13} aria-hidden="true" className="text-primary" />
          {label}
        </label>
        <Input
          id={passwordId}
          type="password"
          // Their own, current password - so the browser's password manager may fill it, never a new one.
          autoComplete="current-password"
          value={confirm.password}
          onChange={(event) => confirm.setPassword(event.target.value)}
          aria-describedby={`${passwordId}-hint`}
        />
        <p
          id={`${passwordId}-hint`}
          className="mt-1 text-[11.5px] text-muted-foreground"
        >
          {hint}
        </p>
      </div>
      {confirm.needsCode && (
        <div>
          <label
            htmlFor={codeId}
            className="mb-1 block text-[12px] font-medium text-foreground"
          >
            Authenticator code
          </label>
          <Input
            id={codeId}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={8}
            value={confirm.code}
            onChange={(event) => confirm.setCode(event.target.value)}
          />
        </div>
      )}
    </div>
  )
}
