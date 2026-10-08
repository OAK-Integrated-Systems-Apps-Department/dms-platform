"use client"

import { useEffect, useRef, useState } from "react"
import {
  Calendar as CalendarIcon,
  ChevronLeft,
  ChevronRight,
} from "lucide-react"

import { cn } from "@/lib/utils"

interface DateTimePickerProps {
  /** "YYYY-MM-DDTHH:mm", or "" for no value selected - same shape a native `<input type="datetime-local">` produces. */
  value: string
  onChange: (value: string) => void
  placeholder?: string
  className?: string
}

const WEEKDAY_LABELS = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"]
const MONTH_LABELS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
]

interface ParsedValue {
  year: number
  month: number
  day: number
  hour: number
  minute: number
}

function parseValue(value: string): ParsedValue | null {
  if (!value) return null
  const [datePart, timePart] = value.split("T")
  const [year, month, day] = datePart.split("-").map(Number)
  const [hour, minute] = (timePart ?? "00:00").split(":").map(Number)
  if ([year, month, day, hour, minute].some((n) => Number.isNaN(n))) return null
  return { year, month: month - 1, day, hour, minute }
}

function formatValue(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number
): string {
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${year}-${pad(month + 1)}-${pad(day)}T${pad(hour)}:${pad(minute)}`
}

function clamp(n: number, min: number, max: number): number {
  if (Number.isNaN(n)) return min
  return Math.min(max, Math.max(min, n))
}

/**
 * Custom calendar + time popover for a "YYYY-MM-DDTHH:mm" value - the same
 * shape a native `<input type="datetime-local">` produces, so callers can
 * swap one for the other without touching how the value is consumed. Built
 * to replace that native control specifically because its calendar/clock
 * picker is the browser/OS's own unstyled widget, out of place next to
 * every other custom-styled control in this app (`SearchableSelect`,
 * `MultiSelect`) - same reasoning that drove those two.
 */
export function DateTimePicker({
  value,
  onChange,
  placeholder = "Select date & time…",
  className,
}: DateTimePickerProps) {
  const parsed = parseValue(value)
  const now = new Date()

  const [open, setOpen] = useState(false)
  const [viewYear, setViewYear] = useState(parsed?.year ?? now.getFullYear())
  const [viewMonth, setViewMonth] = useState(parsed?.month ?? now.getMonth())
  const [hour, setHour] = useState(parsed?.hour ?? 0)
  const [minute, setMinute] = useState(parsed?.minute ?? 0)
  const containerRef = useRef<HTMLDivElement>(null)

  // Re-seed the calendar's view (month shown) and time spinners from the
  // current value every time the popover opens, so a stale in-progress
  // pick from a previous open doesn't linger. Done in the trigger's click
  // handler (the only thing that opens it) rather than in an effect.
  function toggleOpen() {
    if (!open) {
      const current = parseValue(value)
      const today = new Date()
      setViewYear(current?.year ?? today.getFullYear())
      setViewMonth(current?.month ?? today.getMonth())
      setHour(current?.hour ?? 0)
      setMinute(current?.minute ?? 0)
    }
    setOpen(!open)
  }

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setOpen(false)
      }
    }
    document.addEventListener("click", handleClickOutside)
    return () => document.removeEventListener("click", handleClickOutside)
  }, [])

  function selectDay(day: number) {
    onChange(formatValue(viewYear, viewMonth, day, hour, minute))
  }

  /** Time spinners only take effect once a day is already picked - there's no value to attach a bare time to otherwise. */
  function commitTime(nextHour: number, nextMinute: number) {
    setHour(nextHour)
    setMinute(nextMinute)
    if (parsed) {
      onChange(
        formatValue(parsed.year, parsed.month, parsed.day, nextHour, nextMinute)
      )
    }
  }

  function goPrevMonth() {
    if (viewMonth === 0) {
      setViewMonth(11)
      setViewYear((y) => y - 1)
    } else {
      setViewMonth((m) => m - 1)
    }
  }

  function goNextMonth() {
    if (viewMonth === 11) {
      setViewMonth(0)
      setViewYear((y) => y + 1)
    } else {
      setViewMonth((m) => m + 1)
    }
  }

  const totalDays = new Date(viewYear, viewMonth + 1, 0).getDate()
  const leadingBlanks = new Date(viewYear, viewMonth, 1).getDay()
  const cells: (number | null)[] = [
    ...Array.from({ length: leadingBlanks }, () => null),
    ...Array.from({ length: totalDays }, (_, i) => i + 1),
  ]

  const pad = (n: number) => String(n).padStart(2, "0")
  const triggerLabel = parsed
    ? `${parsed.year}-${pad(parsed.month + 1)}-${pad(parsed.day)} ${pad(parsed.hour)}:${pad(parsed.minute)}`
    : placeholder

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      <button
        type="button"
        onClick={toggleOpen}
        className={cn(
          "flex h-[38px] w-full items-center justify-between rounded-btn border bg-muted px-3 text-left text-[13px] text-foreground transition-colors",
          open
            ? "border-primary bg-card ring-3 ring-primary/15"
            : "border-border"
        )}
      >
        <span className={cn("truncate", !parsed && "text-[var(--ink-faint)]")}>
          {triggerLabel}
        </span>
        <CalendarIcon
          size={14}
          className="pointer-events-none ml-2 shrink-0 text-[var(--ink-faint)]"
        />
      </button>

      {open && (
        <div
          className="absolute top-[calc(100%+6px)] left-0 z-30 w-[260px] rounded-md border border-border bg-card p-3 shadow-[var(--ledger-shadow)]"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="mb-2 flex items-center justify-between">
            <button
              type="button"
              onClick={goPrevMonth}
              className="rounded-sm p-1 text-[var(--ink-faint)] hover:bg-muted hover:text-foreground"
            >
              <ChevronLeft size={14} />
            </button>
            <span className="text-[13px] font-semibold text-foreground">
              {MONTH_LABELS[viewMonth]} {viewYear}
            </span>
            <button
              type="button"
              onClick={goNextMonth}
              className="rounded-sm p-1 text-[var(--ink-faint)] hover:bg-muted hover:text-foreground"
            >
              <ChevronRight size={14} />
            </button>
          </div>

          <div className="mb-1 grid grid-cols-7 gap-0.5 text-center text-[10.5px] font-semibold text-[var(--ink-faint)]">
            {WEEKDAY_LABELS.map((label) => (
              <div key={label}>{label}</div>
            ))}
          </div>

          <div className="grid grid-cols-7 gap-0.5">
            {cells.map((day, i) => {
              if (day === null) return <div key={`blank-${i}`} />
              const isSelected =
                parsed?.year === viewYear &&
                parsed?.month === viewMonth &&
                parsed?.day === day
              const isToday =
                now.getFullYear() === viewYear &&
                now.getMonth() === viewMonth &&
                now.getDate() === day
              return (
                <button
                  key={day}
                  type="button"
                  onClick={() => selectDay(day)}
                  className={cn(
                    "flex size-7 items-center justify-center rounded-sm text-[12.5px] text-foreground hover:bg-muted",
                    isSelected &&
                      "bg-primary font-semibold text-primary-foreground hover:bg-primary",
                    !isSelected && isToday && "font-semibold text-primary"
                  )}
                >
                  {day}
                </button>
              )
            })}
          </div>

          <div className="mt-3 flex items-center justify-between gap-2 border-t border-border pt-3">
            <div className="flex items-center gap-1.5">
              <input
                type="number"
                min={0}
                max={23}
                value={pad(hour)}
                onChange={(e) =>
                  commitTime(clamp(Number(e.target.value), 0, 23), minute)
                }
                aria-label="Hour"
                className="h-[30px] w-[42px] [appearance:textfield] rounded-btn border border-border bg-muted px-1 text-center text-[13px] text-foreground outline-none focus-visible:border-primary [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
              />
              <span className="text-[13px] text-muted-foreground">:</span>
              <input
                type="number"
                min={0}
                max={59}
                value={pad(minute)}
                onChange={(e) =>
                  commitTime(hour, clamp(Number(e.target.value), 0, 59))
                }
                aria-label="Minute"
                className="h-[30px] w-[42px] [appearance:textfield] rounded-btn border border-border bg-muted px-1 text-center text-[13px] text-foreground outline-none focus-visible:border-primary [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
              />
            </div>

            {value && (
              <button
                type="button"
                onClick={() => {
                  onChange("")
                  setOpen(false)
                }}
                className="text-[12px] font-semibold text-muted-foreground hover:text-foreground"
              >
                Clear
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
