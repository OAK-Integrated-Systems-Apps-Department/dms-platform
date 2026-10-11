"use client"

import { useEffect, useRef } from "react"

interface RowCheckboxProps {
  checked: boolean
  /** Some but not all - the header's "part of this page" state. */
  indeterminate?: boolean
  onChange: (checked: boolean) => void
  label: string
  disabled?: boolean
}

/**
 * A bare checkbox for a table's selection column. The shared `Checkbox`
 * is a labelled row with no indeterminate state, which a column of them
 * (and a select-page header) needs; the label here is for screen readers.
 */
export function RowCheckbox({ checked, indeterminate = false, onChange, label, disabled }: RowCheckboxProps) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate
  }, [indeterminate])

  return (
    <input
      ref={ref}
      type="checkbox"
      checked={checked}
      disabled={disabled}
      aria-label={label}
      onChange={(event) => onChange(event.target.checked)}
      onClick={(event) => event.stopPropagation()}
      className="size-4 cursor-pointer accent-primary disabled:cursor-not-allowed disabled:opacity-50"
    />
  )
}
