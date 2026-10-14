import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** How a figure should be formatted - mirrors MonitoringMetric.Unit. */
export type MetricUnit =
  | "COUNT"
  | "PER_SECOND"
  | "SECONDS"
  | "BYTES"
  | "RATIO"
  | "DURATION"

/** One current figure. `value` is null when Prometheus has no sample - not the same as zero. */
export interface MonitoringReading {
  id: string
  label: string
  value: number | null
  unit: MetricUnit
  /** True for a bounded 0..1 reading meant to be drawn as a dial. */
  gauge: boolean
  /** Fraction at which it stops being fine; null unless `gauge`. */
  warnAt: number | null
  /** Fraction at which it needs acting on; null unless `gauge`. */
  criticalAt: number | null
}

export interface MonitoringSnapshot {
  /** False when Prometheus could not be reached at all; `error` then says so. */
  reachable: boolean
  error: string | null
  readings: MonitoringReading[]
}

/** @property at epoch seconds */
export interface MonitoringPoint {
  at: number
  value: number | null
}

export interface MonitoringSeries {
  id: string
  label: string
  unit: MetricUnit
  points: MonitoringPoint[]
}

export interface MonitoringSeriesResponse {
  reachable: boolean
  error: string | null
  series: MonitoringSeries[]
}

export const MONITORING_RANGES = ["1h", "6h", "24h", "7d"] as const
export type MonitoringRange = (typeof MONITORING_RANGES)[number]

/**
 * System metrics, via `GET /api/v1/monitoring/*`.
 *
 * Read through dms-core rather than from Prometheus or Grafana directly, so
 * the same authorisation check (platform admin, or the system-health
 * permission) guards these - and neither Prometheus nor Grafana has to be reachable from
 * a browser at all.
 */
export async function fetchMonitoringSnapshot(): Promise<MonitoringSnapshot> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/monitoring/snapshot`,
    undefined,
    "Could not load the system metrics."
  )
}

export async function fetchMonitoringSeries(
  range: MonitoringRange
): Promise<MonitoringSeriesResponse> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/monitoring/series?range=${range}`,
    undefined,
    "Could not load the metric history."
  )
}

/** Formats a reading the way its unit says to. Null stays null - the caller shows "no data". */
export function formatMetric(
  value: number | null,
  unit: MetricUnit
): string | null {
  if (value === null || Number.isNaN(value)) return null
  switch (unit) {
    case "BYTES":
      return formatBytesShort(value)
    case "PER_SECOND":
      return `${round(value, value < 10 ? 2 : 0)}/s`
    case "SECONDS":
      // Sub-second response times read better in milliseconds, which is
      // also how everyone talks about them.
      return value < 1 ? `${Math.round(value * 1000)} ms` : `${round(value, 2)} s`
    case "RATIO":
      return `${round(value * 100, 1)}%`
    case "DURATION":
      return formatDuration(value)
    default:
      return round(value, Number.isInteger(value) ? 0 : 1).toLocaleString()
  }
}

/**
 * "42 s", "17 min", "3 h 05 min", "4 d 2 h". Two units at most: a duration
 * is read for its order of magnitude, and "4 d 2 h 17 min 9 s" is a number to
 * decode rather than a fact to take in.
 */
function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.floor(seconds)} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    const rest = minutes % 60
    return rest ? `${hours} h ${String(rest).padStart(2, "0")} min` : `${hours} h`
  }
  const days = Math.floor(hours / 24)
  const restHours = hours % 24
  return restHours ? `${days} d ${restHours} h` : `${days} d`
}

function round(value: number, places: number): number {
  const factor = 10 ** places
  return Math.round(value * factor) / factor
}

function formatBytesShort(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"]
  let size = bytes
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit++
  }
  return `${round(size, size < 10 && unit > 0 ? 1 : 0)} ${units[unit]}`
}

/** One service and whether it is answering. */
export interface ServiceStatus {
  id: string
  label: string
  up: boolean
  /** True when the service reports its own metrics; false when it was only probed. */
  scraped: boolean
}

export interface ServicesResponse {
  reachable: boolean
  error: string | null
  /** Anything down sorts first. */
  services: ServiceStatus[]
}

/**
 * Which services are up, via `GET /api/v1/monitoring/services`.
 *
 * Covers more than the scrape targets: the services with no metrics
 * endpoint of their own - Keycloak, object storage, the converter, the virus
 * scanner, mail, the web app - are reachability-probed instead, and those
 * are the ones whose failure is least visible from inside the DMS.
 */
export async function fetchMonitoringServices(): Promise<ServicesResponse> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/monitoring/services`,
    undefined,
    "Could not load the service status."
  )
}

/** Actuator's own vocabulary, passed through rather than flattened to a boolean. */
export type HealthStatus = "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN" | string

export interface Dependency {
  id: string
  label: string
  status: HealthStatus
  /** One short line, or null - disk figures, or why something is down. */
  detail: string | null
}

export interface DependencyHealth {
  /** The aggregate: DOWN if any contributor is. */
  status: HealthStatus
  dependencies: Dependency[]
}

/**
 * What the backend thinks of its own dependencies, via
 * `GET /api/v1/monitoring/health`.
 *
 * Not the same question as the Services board. That says a port is
 * listening; this says a query from our own pool, with our own credentials,
 * actually came back. They come apart exactly when it matters - a database
 * that is up but refusing this app's password looks fine from outside.
 */
export async function fetchDependencyHealth(): Promise<DependencyHealth> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/monitoring/health`,
    undefined,
    "Could not load dependency health."
  )
}

export const LOG_LEVELS = ["ERROR", "WARN", "INFO", "DEBUG"] as const
export type LogLevel = (typeof LOG_LEVELS)[number]
export const LOG_RANGES = ["15m", "1h", "6h", "24h", "7d"] as const
export type LogRange = (typeof LOG_RANGES)[number]

export interface LogLine {
  /** Epoch milliseconds. */
  at: number
  /** Null when the line declared none - never guessed. */
  level: string | null
  line: string
}

export interface LogsResponse {
  /** False when the log store could not be asked; `error` then says so. */
  reachable: boolean
  error: string | null
  /** Newest first. */
  lines: LogLine[]
  /** True when more matched than were returned. */
  truncated: boolean
}

/**
 * Recent log lines for one service, via `GET /api/v1/monitoring/logs`.
 *
 * `service` is the compose service name - the same id the Services board
 * already uses - so a tile maps to its logs with no lookup in between.
 */
export async function fetchServiceLogs(params: {
  service: string
  level?: LogLevel
  search?: string
  range: LogRange
  limit?: number
}): Promise<LogsResponse> {
  const query = new URLSearchParams({ service: params.service, range: params.range })
  if (params.level) query.set("level", params.level)
  if (params.search?.trim()) query.set("search", params.search.trim())
  if (params.limit) query.set("limit", String(params.limit))
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/monitoring/logs?${query}`,
    undefined,
    "Could not load the logs."
  )
}

export type AlertState = "firing" | "pending" | "inactive"

export interface AlertRule {
  name: string
  state: AlertState | string
  /** critical | warning | null */
  severity: string | null
  component: string | null
  summary: string | null
  /** For a firing or pending rule: the description with the actual value in it. */
  detail: string | null
  /** Epoch seconds the condition first became true; null unless firing/pending. */
  since: number | null
  /** The rule itself failed to evaluate - it will never fire. Worse than inactive. */
  broken: boolean
  /**
   * The metric the rule reads has no series, so it evaluates fine and has
   * nothing to compare. Prometheus calls that healthy and inactive.
   */
  blind: boolean
  /** The selector the rule reads - what is missing, when `blind`. */
  requires: string | null
}

export interface AlertsResponse {
  /** False when Prometheus could not be asked: whether anything fires is then unknown. */
  reachable: boolean
  error: string | null
  /** Most urgent first. */
  rules: AlertRule[]
}

/**
 * Every alert rule and whether it is firing, via `GET /api/v1/monitoring/alerts`.
 *
 * This is the only place a firing alert is visible: the rules are evaluated
 * by Prometheus, nothing delivers them (no Alertmanager, no email or chat
 * route), and Grafana's own alerting screen is deliberately not exposed.
 */
export async function fetchAlerts(): Promise<AlertsResponse> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/monitoring/alerts`,
    undefined,
    "Could not load the alerts."
  )
}
