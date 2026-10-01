"""Deterministic ground-truth corpus generator (section 5).

The label file and the rendered page are produced by the *same* call:
`build_base_pdf` returns the PDF bytes and the ground-truth dict
together, from the same value table. That is the only arrangement in
which the two cannot drift apart -- a corpus whose labels are written
separately from its documents is a corpus that will eventually be
scoring against the wrong answer without anyone noticing.

Everything is seeded from a fixed integer, so the same corpus is
reproducible byte for byte on any machine. Only the generator and the
label JSON are committed; the rendered binaries are regenerated on
demand (see .gitignore).
"""

from __future__ import annotations

import hashlib
import random
from dataclasses import dataclass, field as dc_field
from datetime import date, timedelta
from pathlib import Path

from . import schema

SEED = 20260925


def stable_index(key: str) -> int:
    """A reproducible small integer from a string.

    Not `hash()`: CPython randomizes string hashing per process unless
    PYTHONHASHSEED is pinned, so `abs(hash(doc_id))` would give a
    different invoice number on every run and silently destroy the one
    property this corpus is built for.
    """
    digest = hashlib.sha256(f"{SEED}:{key}".encode()).digest()
    return int.from_bytes(digest[:4], "big") % 90000 + 1000

A4_WIDTH_PT = 595.0
A4_HEIGHT_PT = 842.0


# ----------------------------------------------------------------------
# Value pools -- small and fixed, so the corpus is reproducible
# ----------------------------------------------------------------------
_VENDORS = [
    "Northwind Trading Ltd", "Contoso Supplies PLC", "Fabrikam Industries",
    "Tailspin Logistics", "Adventure Works Ltd", "Lanka Ceramics (Pvt) Ltd",
]
_PARTIES = [
    "Globex Corporation", "Initech Holdings", "Umbrella Services Ltd",
    "Soylent Foods PLC", "Wayne Enterprises", "Stark Industries Ltd",
]
_DEPARTMENTS = ["Finance", "Procurement", "Legal", "Operations", "Human Resources"]
_COST_CENTRES = ["CC-1042", "CC-2213", "CC-3387", "CC-4450"]
_AUTHORS = ["A. Fernando", "M. Silva", "R. Perera", "K. Jayasuriya"]
_TAG_POOL = ["urgent", "archive", "quarterly", "audited", "renewal"]
_KEYWORD_POOL = ["governance", "retention", "compliance", "security", "privacy"]
_DESCRIPTIONS = [
    "Quarterly settlement for consumable stationery supplies.",
    "Annual maintenance agreement covering all site equipment.",
    "Standard governance policy for internal document handling.",
    "Bulk purchase of packaging materials for the export division.",
]
# The one field per type that is printed in 6pt fine print rather than the
# 9pt body table, so per-field accuracy has a resolution-sensitive member.
# Each is a genuinely small-print field on a real document.
_FINE_PRINT_FIELD = {
    "INVOICE": "cost_centre",
    "CONTRACT": "reference_number",
    "POLICY": "reference_number",
}

# 6pt boilerplate. Its job is to give CER something that only adequate
# resolution can read -- at 9pt the whole corpus scored identically
# regardless of how badly it was degraded.
_FINE_PRINT_TERMS = [
    "Remittance: Bank of Ceylon, Colombo 03, sort 77-11-04, account 8830142297, ref as above.",
    "Registered in England and Wales no. 04482137. VAT registration GB 384 2910 55.",
    "Late payment interest accrues at 8% above base rate per annum under the Late Payment of",
    "Commercial Debts (Interest) Act 1998. Goods remain our property until paid in full.",
]

_LINE_ITEMS = [
    ("A4 Copier Paper 80gsm", 12, "18.50"),
    ("Toner Cartridge HP 26A", 3, "96.00"),
    ("Ring Binders 50mm", 25, "4.20"),
    ("Archive Storage Boxes", 40, "3.75"),
]


def _fmt_date_daymonth(d: date) -> str:
    """Day-first, which is what extraction.py's parser currently assumes.

    Section 3.8 wants this configurable per tenant; until it is, printing
    day-first is what a real document in this deployment looks like, and
    printing anything else would measure the locale bug rather than the
    recognition.
    """
    return d.strftime("%d/%m/%Y")


def _fmt_money(value: float) -> str:
    return f"{value:,.2f}"


def _canonical_money(value: float) -> str:
    return f"{value:.2f}"


# ----------------------------------------------------------------------
# One corpus document
# ----------------------------------------------------------------------
@dataclass
class CorpusDocument:
    doc_id: str
    type_code: str
    condition: str
    fields: dict            # ground truth: field_key -> canonical expected value
    reference_text: str     # ground truth text, in the order it is drawn
    filename: str
    mime_type: str
    # The rendered bytes live on disk, not in this object. A single
    # 300-DPI scan condition is ~26 MB, so a 117-document corpus held in
    # memory would be ~700 MB before a single page is OCR'd -- inside a
    # container budgeted at 8.5 GiB against models that already want
    # ~3.4 GiB. `read()` pulls one document's bytes at the moment it is
    # staged and lets them go again straight after.
    path: "Path | None" = None
    # Conditions the harness knows are degraded, used to score the
    # quality flags in Step 5 without any extra annotation.
    degraded: bool = False
    expected_page_count: int = 1
    # "synthetic" or "real". Metrics are reported per set and never
    # combined into one number: the synthetic set is generated and
    # labelled by the same process that is being measured, so averaging
    # it with real documents would produce a figure that means neither
    # thing. See `realset.py`.
    set_name: str = "synthetic"
    # Real set only: the document's VALUES are handwritten (a printed form
    # filled in by pen), not merely annotated in the margin.
    #
    # OS-NFR-001's 95% bar is for "standard document layouts", which a
    # handwritten entry is not, so the two populations are reported
    # separately and never averaged into the headline. It is a label, not
    # a measurement: PaddleOCR has no handwritten-English model and does
    # not misread these -- it does not detect them at all, so page
    # confidence reads HIGHER on them than on some printed pages and
    # cannot be used to infer this.
    handwritten: bool = False

    def read(self) -> bytes:
        if self.path is None:
            raise RuntimeError(f"{self.doc_id} has not been materialized to disk")
        return self.path.read_bytes()

    def label_dict(self) -> dict:
        return {
            "doc_id": self.doc_id,
            "type_code": self.type_code,
            "condition": self.condition,
            "filename": self.filename,
            "mime_type": self.mime_type,
            "degraded": self.degraded,
            "expected_page_count": self.expected_page_count,
            "set": self.set_name,
            "fields": self.fields,
            "reference_text": self.reference_text,
        }


@dataclass
class _Canvas:
    """Accumulates drawn lines so the reference text is, by construction,
    exactly what is on the page and in the order a reader meets it."""

    lines: list[str] = dc_field(default_factory=list)

    def text(self) -> str:
        return "\n".join(self.lines)


# ----------------------------------------------------------------------
# Value generation
# ----------------------------------------------------------------------
def _invoice_values(rng: random.Random, index: int) -> tuple[dict, dict]:
    """Returns (printed, canonical): what goes on the page, and what the
    pipeline is expected to emit for it."""
    issued = date(2026, 1, 1) + timedelta(days=rng.randrange(0, 300))
    amount = round(rng.uniform(250, 48000), 2)
    currency = rng.choice(schema.OPTIONS["currency"])
    classification = rng.choice(schema.OPTIONS["classification_level"])
    vendor = rng.choice(_VENDORS)
    department = rng.choice(_DEPARTMENTS)
    cost_centre = rng.choice(_COST_CENTRES)
    tags = sorted(rng.sample(_TAG_POOL, 2))
    description = rng.choice(_DESCRIPTIONS)

    printed = {
        "invoice_number": f"INV-2026-{index:05d}",
        "invoice_date": _fmt_date_daymonth(issued),
        "amount": _fmt_money(amount),
        "currency": currency,
        "vendor_supplier_name": vendor,
        "purchase_order_number": f"PO-{rng.randrange(10000, 99999)}",
        "classification_level": classification.replace("_", " ").title(),
        "department": department,
        "cost_centre": cost_centre,
        "tags": ", ".join(tags),
        "description": description,
    }
    canonical = dict(printed)
    canonical["invoice_date"] = issued.isoformat()
    canonical["amount"] = _canonical_money(amount)
    canonical["classification_level"] = classification
    canonical["tags"] = tags
    return printed, canonical


def _contract_values(rng: random.Random, index: int) -> tuple[dict, dict]:
    start = date(2026, 1, 1) + timedelta(days=rng.randrange(0, 200))
    end = start + timedelta(days=rng.choice([365, 730, 1095]))
    review = start + timedelta(days=180)
    value = round(rng.uniform(5000, 900000), 2)
    classification = rng.choice(schema.OPTIONS["classification_level"])
    retention = rng.choice(schema.OPTIONS["retention_class"])
    tags = sorted(rng.sample(_TAG_POOL, 2))

    printed = {
        "contract_party": rng.choice(_PARTIES),
        "contract_value": _fmt_money(value),
        "contract_start_date": _fmt_date_daymonth(start),
        "contract_end_date": _fmt_date_daymonth(end),
        "effective_date": _fmt_date_daymonth(start),
        "expiry_date": _fmt_date_daymonth(end),
        "review_due_date": _fmt_date_daymonth(review),
        "classification_level": classification.replace("_", " ").title(),
        "retention_class": retention.replace("_", " ").title(),
        "department": rng.choice(_DEPARTMENTS),
        "tags": ", ".join(tags),
        "description": rng.choice(_DESCRIPTIONS),
        "reference_number": f"REF-CT-{index:05d}",
    }
    canonical = dict(printed)
    canonical["contract_value"] = _canonical_money(value)
    canonical["contract_start_date"] = start.isoformat()
    canonical["contract_end_date"] = end.isoformat()
    canonical["effective_date"] = start.isoformat()
    canonical["expiry_date"] = end.isoformat()
    canonical["review_due_date"] = review.isoformat()
    canonical["classification_level"] = classification
    canonical["retention_class"] = retention
    canonical["tags"] = tags
    return printed, canonical


def _policy_values(rng: random.Random, index: int) -> tuple[dict, dict]:
    effective = date(2026, 1, 1) + timedelta(days=rng.randrange(0, 200))
    expiry = effective + timedelta(days=1095)
    review = effective + timedelta(days=365)
    classification = rng.choice(schema.OPTIONS["classification_level"])
    retention = rng.choice(schema.OPTIONS["retention_class"])
    tags = sorted(rng.sample(_TAG_POOL, 2))
    keywords = sorted(rng.sample(_KEYWORD_POOL, 2))

    printed = {
        "classification_level": classification.replace("_", " ").title(),
        "department": rng.choice(_DEPARTMENTS),
        "retention_class": retention.replace("_", " ").title(),
        "effective_date": _fmt_date_daymonth(effective),
        "expiry_date": _fmt_date_daymonth(expiry),
        "review_due_date": _fmt_date_daymonth(review),
        "description": rng.choice(_DESCRIPTIONS),
        "author": rng.choice(_AUTHORS),
        "keywords": ", ".join(keywords),
        "tags": ", ".join(tags),
        "reference_number": f"REF-PL-{index:05d}",
    }
    canonical = dict(printed)
    canonical["effective_date"] = effective.isoformat()
    canonical["expiry_date"] = expiry.isoformat()
    canonical["review_due_date"] = review.isoformat()
    canonical["classification_level"] = classification
    canonical["retention_class"] = retention
    canonical["tags"] = tags
    canonical["keywords"] = keywords
    return printed, canonical


_VALUE_BUILDERS = {
    "INVOICE": _invoice_values,
    "CONTRACT": _contract_values,
    "POLICY": _policy_values,
}

_TITLES = {"INVOICE": "INVOICE", "CONTRACT": "SERVICE AGREEMENT", "POLICY": "INTERNAL POLICY"}


# ----------------------------------------------------------------------
# Drawing
# ----------------------------------------------------------------------
def build_base_pdf(
    type_code: str,
    index: int,
    rng: random.Random,
    *,
    page_count: int = 1,
    extra_language_block: str | None = None,
) -> tuple[bytes, dict, str]:
    """Draw one native-text document. Returns (pdf_bytes, canonical_fields, reference_text).

    Font sizes are deliberately realistic rather than generous: the body
    rows are 9pt, which at the *current* ~137 DPI render is about 17
    pixels tall -- right at the edge where recognition starts to degrade.
    A corpus drawn at 14pt would be legible at any DPI and would report
    that the 300 DPI change made no difference, which is precisely the
    wrong answer.
    """
    import fitz

    printed, canonical = _VALUE_BUILDERS[type_code](rng, index)
    canvas = _Canvas()

    doc = fitz.open()
    page = doc.new_page(width=A4_WIDTH_PT, height=A4_HEIGHT_PT)

    def write(x: float, y: float, text: str, size: float = 9.0, bold: bool = False) -> None:
        page.insert_text(
            (x, y), text, fontname="helv" if not bold else "hebo", fontsize=size, color=(0, 0, 0)
        )
        canvas.lines.append(text)

    issuer = printed.get("vendor_supplier_name") or printed.get("contract_party") or "Oak Holdings Ltd"
    write(56, 64, issuer, size=13, bold=True)
    write(56, 80, "15 Harbour Road, Colombo 03", size=8)
    write(56, 92, "VAT Reg. GB 384 2910 55", size=8)
    write(400, 64, _TITLES[type_code], size=16, bold=True)

    y = 132
    fine_print_field = _FINE_PRINT_FIELD[type_code]
    for field_key, _mandatory in schema.TYPE_FIELDS[type_code]:
        if field_key not in printed:
            continue
        if field_key == fine_print_field:
            continue  # drawn once, in the 6pt fine print below
        label = schema.PAGE_LABELS.get(field_key, field_key.replace("_", " ").title())
        write(56, y, f"{label}:", size=9, bold=True)
        write(210, y, printed[field_key], size=9)
        y += 17

    if type_code == "INVOICE":
        y += 14
        write(56, y, "Description", size=9, bold=True)
        write(300, y, "Qty", size=9, bold=True)
        write(360, y, "Unit Price", size=9, bold=True)
        write(450, y, "Line Total", size=9, bold=True)
        y += 15
        for description, qty, unit in _LINE_ITEMS:
            line_total = _canonical_money(qty * float(unit))
            write(56, y, description, size=9)
            write(300, y, str(qty), size=9)
            write(360, y, unit, size=9)
            write(450, y, _fmt_money(float(line_total)), size=9)
            y += 14

    # ---- Fine print, 6pt -------------------------------------------
    # Load-bearing, not decoration. Without it the corpus could not
    # measure resolution at all: at 9pt every degradation condition --
    # 300-DPI scan, 100-DPI scan, 1-bit fax, shadowed photo -- produced
    # byte-identical text and byte-identical CER, because PP-OCRv6 reads
    # crisp 9pt vector text perfectly however it is mangled. A corpus
    # that cannot tell those apart cannot judge Step 4's whole premise.
    #
    # 6pt is where it starts to matter, and it is what real documents
    # actually use for terms, remittance details and tax identifiers.
    # At the renderer's true ~137 DPI it is ~11px tall, marginal; at a
    # real 300 DPI it is ~25px, comfortable. That gap is the measurement.
    y += 22
    fine_field = fine_print_field
    if fine_field in printed:
        label = schema.PAGE_LABELS.get(fine_field, fine_field.replace("_", " ").title())
        write(56, y, f"{label}: {printed[fine_field]}", size=6)
        y += 10
    for line in _FINE_PRINT_TERMS:
        write(56, y, line, size=6)
        y += 9

    y += 8
    write(56, y, "Terms: payment due within 30 days of the invoice date.", size=8)

    if extra_language_block:
        y += 20
        # Latin-script accented text, not Tamil or Sinhala. The runtime
        # image carries no system fonts at all -- only PDF's base-14, so
        # `fc-list` is empty and PyMuPDF has nothing to shape Indic
        # script with. Drawing Tamil here would emit empty boxes and
        # produce a "non-English" fixture containing no non-English text,
        # which is worse than not having the condition.
        #
        # Known gap, carried to Step 7: testing the Tamil (`ta`) model and
        # the Sinhala script-detection path needs a font added to the
        # image first. This condition covers what can honestly be covered
        # today -- diacritic recognition.
        page.insert_text((56, y), extra_language_block, fontname="helv", fontsize=10)
        canvas.lines.append(extra_language_block)

    for extra in range(1, page_count):
        continuation = doc.new_page(width=A4_WIDTH_PT, height=A4_HEIGHT_PT)
        body = (
            f"Continuation sheet {extra + 1} of {page_count}. "
            "This schedule forms part of the agreement above and is "
            "incorporated by reference in its entirety."
        )
        continuation.insert_text((56, 100), body, fontname="helv", fontsize=9)
        canvas.lines.append(body)

    pdf_bytes = doc.tobytes()
    doc.close()
    return pdf_bytes, canonical, canvas.text()
