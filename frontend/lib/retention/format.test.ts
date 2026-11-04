import { describe, expect, it } from "vitest"

import { describeRule, formatPeriod, formatPeriodText } from "./format"

describe("retention period wording", () => {
  it("uses the singular for one and the plural otherwise", () => {
    expect(formatPeriod(1, "years")).toBe("1 year")
    expect(formatPeriod(6, "years")).toBe("6 years")
    expect(formatPeriod(1, "days")).toBe("1 day")
    expect(formatPeriod(18, "months")).toBe("18 months")
  })

  it("turns the server's 'activePeriod' text into proper English, and leaves anything else alone", () => {
    expect(formatPeriodText("1 years")).toBe("1 year")
    expect(formatPeriodText("30 days")).toBe("30 days")
    expect(formatPeriodText(null)).toBe("-")
    expect(formatPeriodText("soon")).toBe("soon")
  })

  it("describes a rule as active then archived, or says there is no archive period", () => {
    expect(describeRule({ activeAmount: 1, activeUnit: "years", archiveAmount: 6, archiveUnit: "years" })).toBe(
      "1 year active, then 6 years archived"
    )
    expect(describeRule({ activeAmount: 30, activeUnit: "days", archiveAmount: 0, archiveUnit: "years" })).toBe("30 days active, no archive period")
  })
})

describe("describeVersionLimits", () => {
  it("says what a rule does to old versions, or nothing when it has no limits", async () => {
    const { describeVersionLimits } = await import("./format")
    expect(describeVersionLimits({ maxVersions: 5, versionAgeAmount: 2, versionAgeUnit: "years" })).toBe(
      "keeps at most 5 versions, removes old versions after 2 years"
    )
    expect(describeVersionLimits({ maxVersions: 1, versionAgeAmount: null, versionAgeUnit: null })).toBe("keeps at most 1 version")
    expect(describeVersionLimits({ maxVersions: null, versionAgeAmount: 30, versionAgeUnit: "days" })).toBe(
      "removes old versions after 30 days"
    )
    expect(describeVersionLimits({ maxVersions: null, versionAgeAmount: null, versionAgeUnit: null })).toBeNull()
  })
})
