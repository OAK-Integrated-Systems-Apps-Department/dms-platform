"use client"

import { useRef, type ClipboardEvent, type KeyboardEvent } from "react"

interface OtpInputProps {
  length?: number
  value: string
  onChange: (value: string) => void
  disabled?: boolean
}

/**
 * Same auto-advance / backspace / paste-splits-across-boxes behavior as
 * the vanilla-JS version in dms-otp.html, rebuilt as a controlled React
 * component (refs instead of querySelectorAll, since this needs to live
 * inside a component that can re-render).
 */
export function OtpInput({ length = 6, value, onChange, disabled }: OtpInputProps) {
  const inputRefs = useRef<Array<HTMLInputElement | null>>([])
  const digits = Array.from({ length }, (_, i) => value[i] ?? "")

  function setDigit(index: number, char: string) {
    const next = digits.slice()
    next[index] = char
    onChange(next.join(""))
  }

  function handleChange(index: number, raw: string) {
    const digit = raw.replace(/[^0-9]/g, "").slice(-1)
    setDigit(index, digit)
    if (digit && index + 1 < length) {
      inputRefs.current[index + 1]?.focus()
    }
  }

  function handleKeyDown(index: number, event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Backspace" && !digits[index] && index > 0) {
      inputRefs.current[index - 1]?.focus()
    }
  }

  function handlePaste(index: number, event: ClipboardEvent<HTMLInputElement>) {
    event.preventDefault()
    const pasted = event.clipboardData.getData("text").replace(/[^0-9]/g, "").split("")
    if (pasted.length === 0) return
    const next = digits.slice()
    pasted.forEach((char, offset) => {
      if (index + offset < length) next[index + offset] = char
    })
    onChange(next.join(""))
    const focusIndex = Math.min(index + pasted.length, length - 1)
    inputRefs.current[focusIndex]?.focus()
  }

  return (
    <div className="mb-[26px] flex justify-center gap-[9px]">
      {digits.map((digit, index) => (
        <input
          key={index}
          ref={(el) => {
            inputRefs.current[index] = el
          }}
          type="text"
          inputMode="numeric"
          maxLength={1}
          autoComplete={index === 0 ? "one-time-code" : "off"}
          disabled={disabled}
          value={digit}
          onChange={(e) => handleChange(index, e.target.value)}
          onKeyDown={(e) => handleKeyDown(index, e)}
          onPaste={(e) => handlePaste(index, e)}
          className={
            "h-[52px] w-[44px] rounded-[10px] border-[1.5px] bg-[var(--secondary)] text-center font-mono text-xl outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15 disabled:opacity-60 " +
            (digit
              ? "border-primary font-bold text-[var(--primary-dark,var(--primary))]"
              : "border-[var(--border-strong)] text-foreground")
          }
        />
      ))}
    </div>
  )
}