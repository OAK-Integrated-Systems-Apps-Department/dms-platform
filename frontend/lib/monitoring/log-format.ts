import type { LogLine } from "@/lib/monitoring/api"

/**
 * A log line broken into the parts a person reads, with the parts a person
 * does not read removed.
 *
 * A raw Spring line carries about sixty characters of preamble -
 *
 *   2026-10-13T03:33:25.553Z  WARN 1 --- [dms-core] [nio-8080-exec-6] c.o.d.c.e.GlobalExceptionHandler  : the message
 *
 * - and the panel already shows the time and the level in their own columns,
 * so printing them again inside the text just pushes the message off the
 * right-hand edge. What is left is the logger (which class said it) and the
 * message (what it said).
 */
export interface ParsedLine {
  /** Short origin, e.g. "GlobalExceptionHandler". Null when the line has none. */
  logger: string | null
  /** The full origin, for a tooltip. */
  loggerFull: string | null
  message: string
}

// Spring Boot 3.4+ / 4: <ts> <LEVEL> <pid> --- [<app>] [<thread>] <logger> : <msg>
const SPRING =
  /^\d{4}-\d{2}-\d{2}T[\d:.]+Z?\s+[A-Z]+\s+\d+\s+---\s+\[[^\]]*\]\s+\[[^\]]*\]\s+(\S+)\s+:\s?([\s\S]*)$/

// Any leading timestamp, in the two shapes the stack produces (ISO with a T,
// or "2026-10-13 03:34:19.427 UTC" from PostgreSQL).
const LEADING_TIMESTAMP =
  /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\s*(?:Z|UTC)?\s*/

// "[5666] ERROR:  " / "[5666] LOG:  " - the process id, and a level word the
// panel prints in its own column.
const POSTGRES_PREFIX = /^\[\d+\]\s+(?:(?:LOG|ERROR|FATAL|PANIC|WARNING|NOTICE|INFO|DEBUG\d?):\s+)?/

export function parseLine(raw: string): ParsedLine {
  const spring = SPRING.exec(raw)
  if (spring) {
    const full = spring[1]
    return {
      logger: full.split(".").pop() ?? full,
      loggerFull: full,
      message: spring[2],
    }
  }
  // Everything else: drop a timestamp the time column already shows, and -
  // for PostgreSQL - the "[pid] LEVEL:" that the level column already shows.
  // Only a level WORD is removed there; STATEMENT:, DETAIL: and HINT: stay,
  // because they say what kind of text follows rather than repeating
  // something printed elsewhere. Left alone otherwise: guessing at structure
  // in a format this does not recognise would corrupt lines that were fine.
  const message = raw
    .replace(LEADING_TIMESTAMP, "")
    .replace(POSTGRES_PREFIX, "")
    .trimStart()
  return { logger: null, loggerFull: null, message }
}

/** Short, stable, non-cryptographic hash - only used to tell lines apart. */
function hash(text: string): string {
  let h = 5381
  for (let i = 0; i < text.length; i++) {
    h = ((h << 5) + h + text.charCodeAt(i)) | 0
  }
  return (h >>> 0).toString(36)
}

/** One row of the panel: a line, or a run of identical ones. */
export interface LogGroup {
  /**
   * Unique within one result, and stable across refreshes: the same line
   * keeps the same key when newer lines arrive above it, so a row someone
   * has expanded stays expanded. Never derived from the row's position for
   * that reason.
   */
  key: string
  entry: LogLine
  parsed: ParsedLine
  /** How many consecutive lines this stands for; 1 for a single line. */
  count: number
  /** The oldest line's time in the run, when count > 1. */
  firstAt: number
}

/**
 * Collapses consecutive identical messages into one row with a count.
 *
 * Without it a single repeating warning - the same rejected request, every
 * few seconds - fills the whole panel, and the one different line that
 * explains it is somewhere below the fold. Only ADJACENT repeats collapse:
 * two identical lines with something between them are two events, and the
 * something is often the point.
 *
 * Lines arrive newest first, so a run's `entry` is its newest line and
 * `firstAt` its oldest.
 */
export function groupLines(lines: LogLine[]): LogGroup[] {
  const groups: LogGroup[] = []
  // Two different lines can share a millisecond and a message prefix - two
  // rejected requests for different paths in the same tick - so timestamp
  // plus a slice of the text is not unique. Hashing the WHOLE line separates
  // those; the counter handles the case where the lines are fully identical
  // and landed in the same millisecond but were not adjacent, so they did
  // not collapse into one group.
  const seen = new Map<string, number>()
  for (const entry of lines) {
    const parsed = parseLine(entry.line)
    const last = groups[groups.length - 1]
    if (
      last &&
      last.entry.level === entry.level &&
      last.parsed.logger === parsed.logger &&
      last.parsed.message === parsed.message
    ) {
      last.count += 1
      last.firstAt = entry.at
    } else {
      const base = `${entry.at}-${hash(entry.line)}`
      const n = seen.get(base) ?? 0
      seen.set(base, n + 1)
      groups.push({
        key: n === 0 ? base : `${base}-${n}`,
        entry,
        parsed,
        count: 1,
        firstAt: entry.at,
      })
    }
  }
  return groups
}
