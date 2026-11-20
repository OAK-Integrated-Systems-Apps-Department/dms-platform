"use client"

import { useState } from "react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { saveDownloadedFile } from "@/lib/download-file"
import { generateSample } from "@/lib/reports/api"
import { reportError } from "@/lib/reports/messages"

import { ChoiceRow, MultiChoiceRow, Row, Section, SettingsShell, TextRow, ToggleRow } from "./settings-ui"
import { useSettingsTab } from "./use-settings-tab"

const KEYS = [
  "allowedFormats", "defaultFormat", "csvDelimiter", "csvIncludeBom", "headerText", "footerText", "confidentialityNotice",
  "showLogo", "showFilterSummary", "showGeneratedBy", "pdfPageSize", "pdfOrientation",
] as const

const FORMATS = [{ value: "pdf", label: "PDF" }, { value: "xlsx", label: "Excel" }, { value: "csv", label: "CSV" }]
const str = (v: unknown) => (typeof v === "string" ? v : "")
const list = (v: unknown) => (Array.isArray(v) ? (v as string[]) : [])

/** Which file formats exist, how CSV and PDF look, the header, footer and notice, and a sample made from what is on screen. */
export function OutputTab({ canEdit = true }: { canEdit?: boolean }) {
  const tab = useSettingsTab(KEYS)
  const [making, setMaking] = useState<"pdf" | "csv" | null>(null)
  const locked = !canEdit || tab.saving
  const p = tab.problems
  const allowed = list(tab.value("allowedFormats"))

  async function sample(format: "pdf" | "csv") {
    setMaking(format)
    try {
      saveDownloadedFile(await generateSample(format, tab.merged()))
    } catch (error) {
      toast.error(reportError(error, "Could not make the sample."))
    } finally {
      setMaking(null)
    }
  }

  return (
    <SettingsShell tab={tab} canEdit={canEdit}>
      <Section title="File formats" hint="What people can choose when they run or schedule a report.">
        <MultiChoiceRow title="Allowed formats" value={allowed} options={FORMATS} onChange={(v) => tab.set("allowedFormats", v)}
          disabled={locked} problem={p.allowedFormats} />
        <ChoiceRow title="Default format" value={str(tab.value("defaultFormat"))} options={FORMATS.filter((f) => allowed.includes(f.value))}
          onChange={(v) => tab.set("defaultFormat", v)} disabled={locked} problem={p.defaultFormat} />
      </Section>
      <Section title="CSV">
        <ChoiceRow title="CSV delimiter" description="Excel in some regions expects a semicolon." value={str(tab.value("csvDelimiter"))}
          options={[{ value: ",", label: "Comma" }, { value: ";", label: "Semicolon" }, { value: "\t", label: "Tab" }]}
          onChange={(v) => tab.set("csvDelimiter", v)} disabled={locked} problem={p.csvDelimiter} />
        <ToggleRow title="Byte-order mark" description="Lets Excel open accented and Sinhala text correctly."
          checked={tab.value("csvIncludeBom") === true} onChange={(v) => tab.set("csvIncludeBom", v)} disabled={locked} problem={p.csvIncludeBom} />
      </Section>
      <Section title="PDF and branding">
        <ChoiceRow title="PDF page size" value={str(tab.value("pdfPageSize"))} options={[{ value: "A4", label: "A4" }, { value: "Letter", label: "Letter" }]}
          onChange={(v) => tab.set("pdfPageSize", v)} disabled={locked} problem={p.pdfPageSize} />
        <ChoiceRow title="PDF orientation" value={str(tab.value("pdfOrientation"))}
          options={[{ value: "landscape", label: "Landscape" }, { value: "portrait", label: "Portrait" }]}
          onChange={(v) => tab.set("pdfOrientation", v)} disabled={locked} problem={p.pdfOrientation} />
        <ToggleRow title="Show the logo" checked={tab.value("showLogo") === true} onChange={(v) => tab.set("showLogo", v)} disabled={locked} problem={p.showLogo} />
        <ToggleRow title="Show the filter summary" description="The sentence that says what the report was filtered by."
          checked={tab.value("showFilterSummary") === true} onChange={(v) => tab.set("showFilterSummary", v)} disabled={locked} problem={p.showFilterSummary} />
        <ToggleRow title="Show who generated it" checked={tab.value("showGeneratedBy") === true} onChange={(v) => tab.set("showGeneratedBy", v)}
          disabled={locked} problem={p.showGeneratedBy} />
        <TextRow title="Header text" value={str(tab.value("headerText"))} maxLength={200} onChange={(v) => tab.set("headerText", v)} disabled={locked} problem={p.headerText} />
        <TextRow title="Footer text" value={str(tab.value("footerText"))} maxLength={200} onChange={(v) => tab.set("footerText", v)} disabled={locked} problem={p.footerText} />
        <TextRow title="Confidentiality notice" description="Printed on every PDF page." multiline maxLength={500} width="w-[380px]"
          value={str(tab.value("confidentialityNotice"))} onChange={(v) => tab.set("confidentialityNotice", v)} disabled={locked} problem={p.confidentialityNotice} />
      </Section>
      <Section title="Live sample" hint="Five invented rows with your settings as they are on screen, even if not yet saved. No organisation data is used.">
        <Row title="Download a sample" description="Check the header, footer, notice and layout before you save.">
          <div className="flex gap-2">
            <Button variant="outline" disabled={making !== null || !allowed.includes("pdf")} onClick={() => void sample("pdf")}>
              {making === "pdf" ? "Making…" : "Download sample PDF"}
            </Button>
            <Button variant="outline" disabled={making !== null || !allowed.includes("csv")} onClick={() => void sample("csv")}>
              {making === "csv" ? "Making…" : "Download sample CSV"}
            </Button>
          </div>
        </Row>
      </Section>
    </SettingsShell>
  )
}
