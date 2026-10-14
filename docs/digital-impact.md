# Environmental Impact

The dashboard's Environmental Impact section shows the pages of documents processed digitally in a
period, and the paper and footprint they stand for. This page explains where the numbers come
from and how to set your organisation's own assumptions.

## Figures and how sure they are

| Figure | Kind | How |
|---|---|---|
| Pages processed | **Measured** | Pages of every file version uploaded in the period, read from the file |
| Sheets of paper | Calculated | Pages ÷ pages per sheet |
| Paper avoided | Calculated | Pages × grams per page (paper size × gsm ÷ pages per sheet) |
| CO₂e avoided | **Estimated** | Paper kg × CO₂e factor |
| Water footprint | **Estimated** | Paper kg × water factor |

The dashboard always says "estimated avoided", never "saved": the factors are published
averages, not measurements of your supply chain.

## Where page counts come from (V71)

`PageCountBackfill` reads each scanned-clean version once, in the background (every 2 minutes, 50
at a time), and stores `page_count`, `page_count_source` and `pages_counted_at` on
`document_versions`. Pages are never estimated from file size.

| Format | Source | How |
|---|---|---|
| PDF | counted | PDFBox page count |
| PPTX | counted | Number of `ppt/slides/slideN.xml` parts |
| TIFF | counted | Image frames |
| DOCX | reported | `<Pages>` in `docProps/app.xml`, as Word saved it |
| ODT, ODP | reported | `meta:page-count` in `meta.xml` |
| DOC, PPT | reported | Summary information page / slide count |
| Spreadsheets, images, text, email, ZIP | none | No pages - left out, not counted as one each |

Files that can't be read (damaged, encrypted) are marked `none`. The dashboard shows how many
of the period's files have a page count, so the figures can be read honestly.

## Scope

- Only documents the viewer can open count (the dashboard's usual access rules).
- A page is "processed" when a version containing it is uploaded.
- Pages of documents deleted since aren't counted, so the figures can only understate.

## Configuration (`dms.impact.*`)

| Property | Default | Environment variable |
|---|---|---|
| `calculation-version` | `1` | `IMPACT_CALCULATION_VERSION` - raise it when the method changes |
| `paper-size` / `paper-width-mm` / `paper-height-mm` | A4, 210 × 297 | `IMPACT_PAPER_SIZE`, `IMPACT_PAPER_WIDTH_MM`, `IMPACT_PAPER_HEIGHT_MM` |
| `paper-gsm` | 80 | `IMPACT_PAPER_GSM` |
| `pages-per-sheet` | 2 (double-sided) | `IMPACT_PAGES_PER_SHEET` |
| `co2e-kg-per-kg-paper` | 1.08 | `IMPACT_CO2E_KG_PER_KG_PAPER` |
| `co2e-source` | EPA Victoria office copy paper, low end of 1.08-1.52 | `IMPACT_CO2E_SOURCE` |
| `water-litres-per-kg-paper` | 300 | `IMPACT_WATER_LITRES_PER_KG_PAPER` |
| `water-source` | Water Footprint Network printing and writing paper, low end of 300-2,600 L/kg | `IMPACT_WATER_SOURCE` |
| `page-count-interval` | PT2M | `IMPACT_PAGE_COUNT_INTERVAL` |
| `page-count-max-bytes` | 500 MB | `IMPACT_PAGE_COUNT_MAX_BYTES` |

The defaults are the low end of published ranges, so the figures understate rather than
overstate. Set your own if your organisation has supplier-specific factors.

## API

`GET /api/v1/dashboard/impact?timeZone=…&days=7|30|90` or `&from=yyyy-mm-dd&to=yyyy-mm-dd`
(custom, at most 366 days, not in the future). It returns the totals, the period before for
comparison, all-time totals, coverage, the period's pages by kind of file (PDF, Word,
presentations, scans), the five documents with the most pages, a daily trend, and the method.

## Background image

The Environmental Impact band uses `frontend/public/images/oak-leaves.jpg` - "Close up of Oak Leaves and
Acorns", [Pexels photo 18760063](https://www.pexels.com/photo/close-up-of-oak-leaves-and-acorns-18760063/),
under the [Pexels licence](https://www.pexels.com/license/) (free for commercial use, no attribution
required). Replace the file with your own brand photo to change it; the green overlay in
`components/dashboard/dashboard.css` (`.impact-band`) keeps the figures readable.
