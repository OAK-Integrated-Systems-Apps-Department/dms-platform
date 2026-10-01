"""`python -m eval.run` -- score the pipeline over the evaluation sets.

There are two sets and they are **never combined into one number**:

- **synthetic** -- 117 generated documents. A regression set. It is built
  and labelled by the same process that is being measured, so it can
  prove a change *broke* something but it cannot prove a change helps
  real documents. Reproducible from a seed; safe to commit in full.
- **real** -- documents supplied by a human and labelled by a human
  (`eval/corpus/real/`, `python -m eval.draft_labels`). This is the set
  that answers OS-NFR-001 and the set a keep-or-drop decision rests on.
  Its documents, labels and per-document results are all gitignored:
  an invoice carries customer names, bank details and amounts, and so
  does anything derived from one.

Averaging the two would produce a figure that means neither thing, and
whose value would move whenever someone added a document to either set.

Typical use:

    python -m eval.run --variant baseline
    python -m eval.run --variant dpi300 --set synthetic --only rotated_90
    python -m eval.compare eval/reports/baseline.json eval/reports/dpi300.json
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from . import conditions as conditions_mod
from . import harness, metrics, realset, rss, schema as schema_mod
from . import staleness

REPORTS_DIR = Path(__file__).resolve().parent / "reports"

SYNTHETIC = "synthetic"
REAL = "real"


# ----------------------------------------------------------------------
# Variants
# ----------------------------------------------------------------------
# A variant is a set of Settings overrides, so "does this change help?"
# is one flag on the command line rather than an edited file. Every
# accuracy change in Phase 2 gets an entry here and a before/after pair of
# reports, per section 5's requirement that each toggle be justified.
VARIANTS: dict[str, dict] = {
    # Deliberately empty: whatever config.py's defaults are at the time of
    # the run, so a run of this variant cannot silently disagree with
    # production.
    #
    # NOTE that this means "baseline" is a moving target by design --
    # `reports/baseline-2026-09-25.json` is the *recorded* Phase 2
    # starting point, taken before any accuracy change existed, and later
    # runs of this variant will (intentionally) differ from it as each
    # step lands. Use `no_orientation` below to reproduce the recorded
    # file's configuration.
    "baseline": {},
    # Step 3's control. The recorded baseline was taken when whole-page
    # orientation correction did not exist at all; this is the nearest
    # reproducible equivalent now that it does and is on by default.
    "no_orientation": {"ocr_orientation_enabled": False},
    # Step 7's control, and the reason it exists rather than comparing
    # against an older report: `baseline-2026-09-25.json` predates BOTH
    # orientation correction and the render-budget reshape, so a straight
    # before/after against it would attribute three changes' worth of
    # movement to routing. This variant differs from `baseline` in
    # exactly one setting, so the difference is exactly routing.
    "no_routing": {"ocr_routing_enabled": False},
}


def build_settings(variant: str):
    from ocr_worker.config import Settings

    if variant not in VARIANTS:
        raise SystemExit(f"unknown variant {variant!r}; known: {', '.join(sorted(VARIANTS))}")
    return Settings(**VARIANTS[variant])


def settings_fingerprint(settings) -> dict:
    """The settings that can plausibly move an accuracy number.

    Recorded in every report so a comparison can never be made between
    two runs whose configuration differed in a way nobody noticed -- which
    is exactly the mistake a "before/after" workflow invites.
    """
    keys = [
        "ocr_render_dpi",
        "ocr_languages",
        "min_confidence_threshold",
        "field_low_confidence_threshold",
        "ocr_max_pages",
        "ocr_timeout_base_s",
        "ocr_timeout_per_page_s",
        # Preprocessing toggles (section 3.4). Every one of these moves an
        # accuracy number by construction, so each must appear here as it
        # is added -- a fingerprint that omits a toggle is worse than no
        # fingerprint, because it makes two incomparable runs look
        # comparable.
        "ocr_orientation_enabled",
        "ocr_orientation_min_confidence",
        "ocr_render_max_mp",
        "ocr_routing_enabled",
        "ocr_routing_max_garbage_ratio",
        "ocr_routing_min_plausibility",
        "ocr_routing_min_uncovered_image",
    ]
    return {key: getattr(settings, key) for key in keys if hasattr(settings, key)}


# ----------------------------------------------------------------------
# Preprocessing accounting
# ----------------------------------------------------------------------
def metrics_orientation_counts() -> dict:
    return {
        "pages_total": 0,
        "pages_classified": 0,
        "pages_native_text": 0,
        "upright_pages": 0,
        "upright_pages_rotated": 0,   # the false-rotation numerator
        "rotated_pages": 0,
        "rotated_pages_corrected": 0,
        "by_applied_angle": {},
        "wrongly_rotated": [],
    }


def accumulate_orientation(counts: dict, doc, output: dict) -> None:
    """Folds one document's per-page orientation decisions into `counts`.

    Ground truth comes from the condition name -- the synthetic corpus's
    `rotated_90/180/270` are the only conditions that carry a real
    quarter turn, and `skewed_2deg` deliberately does not (a 2-degree
    skew must never be quarter-turned). A real document has no known
    truth, so it only contributes to the page tallies.
    """
    condition = getattr(doc, "condition", "") or ""
    truth = int(condition.split("_")[1]) if condition.startswith("rotated_") else 0
    known_truth = condition != "" and not condition.startswith("real")

    for page in output.get("pages") or []:
        counts["pages_total"] += 1
        decision = (page.get("preprocessing") or {}).get("orientation")
        if decision is None:
            counts["pages_native_text"] += 1
            continue
        counts["pages_classified"] += 1
        applied_angle = decision["angle"] if decision.get("applied") else 0
        key = str(applied_angle)
        counts["by_applied_angle"][key] = counts["by_applied_angle"].get(key, 0) + 1
        if not known_truth:
            continue
        if truth == 0:
            counts["upright_pages"] += 1
            if applied_angle != 0:
                counts["upright_pages_rotated"] += 1
                counts["wrongly_rotated"].append(
                    {
                        "doc_id": doc.doc_id,
                        "page": page.get("page_number"),
                        "angle": decision["angle"],
                        "confidence": decision.get("confidence"),
                    }
                )
        else:
            counts["rotated_pages"] += 1
            if applied_angle == truth:
                counts["rotated_pages_corrected"] += 1


def finalize_orientation(counts: dict) -> dict:
    upright = counts["upright_pages"]
    rotated = counts["rotated_pages"]
    counts["false_rotation_rate"] = (
        round(counts["upright_pages_rotated"] / upright, 4) if upright else None
    )
    counts["correction_rate"] = (
        round(counts["rotated_pages_corrected"] / rotated, 4) if rotated else None
    )
    return counts


# ----------------------------------------------------------------------
# Scoring one set
# ----------------------------------------------------------------------
def score_set(set_name: str, results, documents) -> tuple[dict, list[dict]]:
    """Returns (summary, per-document rows).

    The rows are returned separately from the summary because the real
    set's rows carry customer data and are written to a gitignored file,
    while the summary is safe to commit.
    """
    by_id = {doc.doc_id: doc for doc in documents}

    field_counts: dict[str, metrics.FieldCounts] = {}
    # Real set only: the same field-weighted tally, kept separately for
    # handwritten and printed documents.
    #
    # Field-weighted and not an average of the per-document exact-match
    # rates in `per_condition` below, because the headline is
    # field-weighted -- averaging document means would give a 3-field
    # receipt the same weight as a 10-field invoice and the split would
    # not reconcile with the combined number it is splitting.
    field_counts_by_population: dict[str, dict[str, metrics.FieldCounts]] = {
        "printed": {},
        "handwritten": {},
    }
    docs_by_population: dict[str, int] = {"printed": 0, "handwritten": 0}
    doc_latency = metrics.LatencySummary()
    page_latency = metrics.LatencySummary()
    quality_flag = metrics.BinaryCounts()
    # Searchable PDF rendition (production prompt 3.11). Counted per
    # document, because a rendition that failed is invisible in every
    # other number here -- accuracy, CER and latency are all unaffected
    # by it, so a rendition bug can pass the whole harness untouched.
    #
    # It did. `show_pdf_page` rejects a non-PDF source, so EVERY image
    # upload silently produced no rendition at all, and nothing in this
    # report moved. The flag existed; nothing read it.
    rendition_ok = 0
    rendition_failed = 0
    rendition_reasons: dict[str, int] = {}

    per_condition: dict[str, dict] = {}
    per_type: dict[str, dict] = {}
    rows: list[dict] = []
    status_totals: dict[str, int] = {}
    scored_text = 0
    # Labels whose `reference_text` is present but unusable. Reported in
    # the summary so a dropped CER is visible as a label to fix, not as a
    # document that quietly never had one.
    unusable_references: list[str] = []
    # Preprocessing accounting (section 3.4: "every preprocessing toggle
    # must be justified by a before/after eval report"). For orientation
    # the justifying number is not how many rotated pages were fixed --
    # the per-condition table already shows that -- but how many UPRIGHT
    # pages were rotated by mistake, because that error costs as much as
    # the correction gains. Without this, a stage that silently started
    # rotating good pages would show up only as a diffuse accuracy drop
    # with nothing pointing at the cause.
    orientation = metrics_orientation_counts()

    for result in results:
        doc = by_id[result.doc_id]
        output = result.output or {}
        text = output.get("text") or ""
        fields = output.get("fields") or {}
        flags = output.get("flags") or {}

        # A real document only has CER/WER if someone pasted its text into
        # the label. Scoring an empty reference as 0.0 would silently
        # report a perfect character error rate for every unlabelled one.
        #
        # A reference that is present but is not the page's text is worse
        # than an absent one: it produces a confident number that measures
        # the label rather than the engine. Those are skipped and named.
        reference_problem = metrics.reference_text_problem(doc.reference_text)
        has_reference = bool(doc.reference_text.strip()) and reference_problem is None
        if reference_problem:
            unusable_references.append(f"{doc.doc_id}: {reference_problem}")
        cer = metrics.character_error_rate(doc.reference_text, text) if has_reference else None
        wer = metrics.word_error_rate(doc.reference_text, text) if has_reference else None
        if has_reference:
            scored_text += 1

        accumulate_orientation(orientation, doc, output)
        metrics.accumulate_field_counts(field_counts, doc.fields, fields, result.field_statuses)
        if set_name == REAL:
            population = "handwritten" if doc.handwritten else "printed"
            docs_by_population[population] += 1
            metrics.accumulate_field_counts(
                field_counts_by_population[population],
                doc.fields,
                fields,
                result.field_statuses,
            )
        doc_latency.add(result.elapsed_s)
        if result.page_count:
            page_latency.add(result.elapsed_s / result.page_count)

        # Only the synthetic set knows which documents it degraded, so
        # only it can calibrate the quality flags.
        if set_name == SYNTHETIC:
            quality_flag.add(bool(flags.get("poor_image_quality")), doc.degraded)

        # Only completed documents can have a rendition; a failed one had
        # nothing to render, and counting it would make an unrelated
        # failure look like a rendition bug.
        if result.status == "completed":
            if flags.get("searchable_pdf_failed"):
                rendition_failed += 1
                for reason in flags.get("searchable_pdf_failure_reasons") or ["(no reason given)"]:
                    # Group by the reason's shape, not its full text: a
                    # per-page reason carries a page number, so the raw
                    # strings are all distinct and a tally of them says
                    # nothing.
                    key = str(reason).split(":", 2)[-1].strip()[:80] if ":" in str(reason) else str(reason)[:80]
                    rendition_reasons[key] = rendition_reasons.get(key, 0) + 1
            else:
                rendition_ok += 1

        status_totals[result.status] = status_totals.get(result.status, 0) + 1

        # Same exclusion `accumulate_field_counts` applies, for the same
        # reason and so the two cannot disagree. Without it a document
        # whose every OCR field was right scored 6/10 purely because four
        # of its labels name fields OCR is not asked to read -- and
        # `requires_review`'s recall then reported a missed flag on a
        # document that had nothing wrong with it.
        statuses = result.field_statuses or {}
        scoreable = {
            key: expected
            for key, expected in doc.fields.items()
            if statuses.get(key) != "not_extractable"
        }
        correct = sum(
            1
            for key, expected in scoreable.items()
            if key in fields and metrics.field_matches(expected, (fields[key] or {}).get("value"))
        )
        exact = correct / len(scoreable) if scoreable else 0.0

        for bucket, key in ((per_condition, result.condition), (per_type, result.type_code)):
            entry = bucket.setdefault(
                key, {"docs": 0, "cer": [], "wer": [], "exact": [], "elapsed": [], "failed": 0}
            )
            entry["docs"] += 1
            if cer is not None:
                entry["cer"].append(cer)
                entry["wer"].append(wer)
            entry["exact"].append(exact)
            entry["elapsed"].append(result.elapsed_s)
            if result.status != "completed":
                entry["failed"] += 1

        rows.append(
            {
                "doc_id": result.doc_id,
                "set": set_name,
                "type": result.type_code,
                "condition": result.condition,
                "status": result.status,
                "error": result.error,
                "pages": result.page_count,
                "elapsed_s": round(result.elapsed_s, 3),
                "cer": round(cer, 4) if cer is not None else None,
                "wer": round(wer, 4) if wer is not None else None,
                "fields_expected": len(scoreable),
                "fields_correct": correct,
                "field_exact_match": round(exact, 4),
                "requires_review": bool(flags.get("requires_review")),
                "review_reasons": list(flags.get("reasons") or []),
                "missing_mandatory": list(flags.get("missing_mandatory") or []),
                # Per-page routing decision, so "which way did THIS
                # fixture go, and on what numbers" is answerable from the
                # committed report rather than by re-running.
                "routing": [
                    {
                        "page": page.get("page_number"),
                        "method": page.get("method"),
                        **{
                            key: value
                            for key, value in (page.get("routing") or {}).items()
                            if key in ("route", "reason", "garbage_ratio", "plausibility",
                                       "text_coverage", "uncovered_image_coverage", "ocr_regions")
                        },
                    }
                    for page in (output.get("pages") or [])
                ],
                # The raw expected/actual pair per field, so a report can
                # be re-scored offline when the comparator itself turns
                # out to be wrong -- which has already happened twice.
                "fields": {
                    key: {
                        "expected": expected,
                        "actual": (fields.get(key) or {}).get("value"),
                        "status": (result.field_statuses or {}).get(key),
                    }
                    for key, expected in doc.fields.items()
                },
            }
        )

    def summarize(bucket: dict) -> dict:
        out = {}
        for key, entry in sorted(bucket.items()):
            n = entry["docs"]
            cers = entry["cer"]
            out[key] = {
                "docs": n,
                "failed": entry["failed"],
                "cer_mean": round(sum(cers) / len(cers), 4) if cers else None,
                "wer_mean": round(sum(entry["wer"]) / len(entry["wer"]), 4) if cers else None,
                "field_exact_match": round(sum(entry["exact"]) / n, 4),
                "latency_p50": round(metrics.percentile(entry["elapsed"], 50), 2),
                "latency_p95": round(metrics.percentile(entry["elapsed"], 95), 2),
            }
        return out

    all_cer = [row["cer"] for row in rows if row["cer"] is not None]
    all_wer = [row["wer"] for row in rows if row["wer"] is not None]
    all_exact = [row["field_exact_match"] for row in rows]

    overall_expected = sum(c.expected for c in field_counts.values())
    overall_correct = sum(c.correct for c in field_counts.values())
    overall_extracted = sum(c.status_extracted for c in field_counts.values())
    overall_extracted_correct = sum(c.status_extracted_correct for c in field_counts.values())

    def population_summary(name: str) -> dict:
        counts = field_counts_by_population[name]
        expected = sum(c.expected for c in counts.values())
        correct = sum(c.correct for c in counts.values())
        extracted = sum(c.status_extracted for c in counts.values())
        extracted_correct = sum(c.status_extracted_correct for c in counts.values())
        return {
            "documents": docs_by_population[name],
            "fields_expected": expected,
            "fields_correct": correct,
            "field_accuracy": round(correct / expected, 4) if expected else None,
            "extracted_precision": (
                round(extracted_correct / extracted, 4) if extracted else None
            ),
        }

    summary = {
        "set": set_name,
        "synthetic": set_name == SYNTHETIC,
        "documents": len(rows),
        "documents_with_reference_text": scored_text,
        "unusable_reference_text": unusable_references,
        "types": sorted({r["type"] for r in rows}),
        "conditions": sorted({r["condition"] for r in rows}),
        "status_totals": status_totals,
        "preprocessing": {"orientation": finalize_orientation(orientation)},
        "headline": {
            # The OS-NFR-001 number, for this set only: across every
            # labelled field, how often was the emitted value exactly right.
            "field_accuracy": round(overall_correct / overall_expected, 4) if overall_expected else None,
            "extracted_precision": (
                round(overall_extracted_correct / overall_extracted, 4) if overall_extracted else None
            ),
            "cer_mean": round(sum(all_cer) / len(all_cer), 4) if all_cer else None,
            "wer_mean": round(sum(all_wer) / len(all_wer), 4) if all_wer else None,
            "cer_p95": round(metrics.percentile(all_cer, 95), 4) if all_cer else None,
            "field_exact_match_mean": round(sum(all_exact) / len(all_exact), 4) if all_exact else None,
        },
        "latency": {
            "per_document_s": doc_latency.to_dict(),
            "per_page_s": page_latency.to_dict(),
        },
        "fields": {key: counts.to_dict() for key, counts in sorted(field_counts.items())},
        "by_condition": summarize(per_condition),
        "by_type": summarize(per_type),
    }
    if set_name == REAL:
        # `requires_review` is a ROUTING decision, so the question it has
        # to answer is not "how confident was the model" but "did sending
        # this document to a human turn out to be the right call".
        # Precision = of those flagged, how many really had a wrong or
        # missing field. Recall = of those that did, how many were caught.
        #
        # Recall is the one that matters: a missed flag is a wrong value
        # filed as correct. Precision only costs reviewer time -- but a
        # flag that fires on everything carries no information at all, and
        # that is the failure mode to watch for here.
        review_flag = metrics.BinaryCounts()
        reason_totals: dict[str, int] = {}
        missing_totals: dict[str, int] = {}
        for row in rows:
            had_error = row["fields_correct"] < row["fields_expected"]
            review_flag.add(row["requires_review"], had_error)
            for reason in row.get("review_reasons") or []:
                reason_totals[reason] = reason_totals.get(reason, 0) + 1
            for key in row.get("missing_mandatory") or []:
                missing_totals[key] = missing_totals.get(key, 0) + 1
        summary["review_flag"] = {
            **review_flag.to_dict(),
            "flagged": review_flag.tp + review_flag.fp,
            "documents_with_an_error": review_flag.tp + review_flag.fn,
            "reasons": dict(sorted(reason_totals.items(), key=lambda kv: -kv[1])),
            "missing_mandatory": dict(sorted(missing_totals.items(), key=lambda kv: -kv[1])),
        }
        # Printed is the population OS-NFR-001's "standard document
        # layouts" actually refers to. Handwritten is reported beside it
        # rather than dropped, because the bar being inapplicable is not a
        # reason to stop measuring -- the right outcome for a handwritten
        # value is a review flag, and that has to be observable.
        summary["by_population"] = {
            "printed": population_summary("printed"),
            "handwritten": population_summary("handwritten"),
        }
    if set_name == SYNTHETIC:
        summary["quality_flag"] = quality_flag.to_dict()
    attempted = rendition_ok + rendition_failed
    summary["searchable_pdf"] = {
        "attempted": attempted,
        "succeeded": rendition_ok,
        "failed": rendition_failed,
        # None rather than 1.0 when nothing was attempted: a rate over
        # zero documents is not a perfect score, it is no measurement.
        "success_rate": round(rendition_ok / attempted, 4) if attempted else None,
        "failure_reasons": dict(sorted(rendition_reasons.items(), key=lambda kv: -kv[1])),
    }
    return summary, rows


# ----------------------------------------------------------------------
# Markdown rendering
# ----------------------------------------------------------------------
def _pct(value) -> str:
    return "n/a" if value is None else f"{value:.1%}"


def _num(value, places: int = 4) -> str:
    return "n/a" if value is None else f"{value:.{places}f}"


def _n_of(summary: dict) -> str:
    """`(n=K)` for the text metrics, so a mean over one document cannot
    be read as a mean over the set."""
    scored = summary.get("documents_with_reference_text") or 0
    return f"(n={scored})" if scored else ""


def render_markdown(report: dict) -> str:
    lines = [
        f"# OCR eval report -- `{report['variant']}`",
        "",
        f"Generated {report['generated_at']} - {report['wall_clock_s']}s wall clock.",
        "",
        f"Settings: `{json.dumps(report['settings'])}`",
        "",
        "> **Two sets, never combined.** The synthetic set is generated and",
        "> labelled by the same process being measured -- it is a regression",
        "> set that can veto a change, not evidence that one helps. The real",
        "> set is the one a keep-or-drop decision rests on.",
        "",
    ]

    for set_name in (REAL, SYNTHETIC):
        summary = report["sets"].get(set_name)
        lines.append(f"## {set_name.capitalize()} set")
        lines.append("")
        if summary is None:
            lines += [f"_Not run in this pass (`--set` excluded it)._", ""]
            continue
        if summary["documents"] == 0:
            if set_name == REAL:
                lines += [
                    "**No reviewed real documents yet.** Put documents in",
                    "`eval/corpus/real/`, run `python -m eval.draft_labels`, correct each",
                    "label and set `\"reviewed\": true`.",
                    "",
                    "Until this set has data, a synthetic-only result can **veto** a change",
                    "(a regression fails the gate) but cannot **justify** one.",
                    "",
                ]
            else:
                lines += ["_No documents._", ""]
            if report.get("real_pending"):
                lines += [
                    f"Awaiting review ({len(report['real_pending'])}): "
                    + ", ".join(f"`{d}`" for d in report["real_pending"][:20]),
                    "",
                ]
            continue

        head = summary["headline"]
        banner = (
            "SYNTHETIC -- regression only, not real-world accuracy"
            if set_name == SYNTHETIC
            else "REAL -- human-labelled; this is the decision set"
        )
        lines += [
            f"_{banner}_",
            "",
            "| metric | value |",
            "|---|---|",
            f"| documents | {summary['documents']} |",
            f"| field accuracy (OS-NFR-001) | **{_pct(head['field_accuracy'])}** |",
            f"| `extracted`-status precision | {_pct(head['extracted_precision'])} |",
            # The sample count rides along with each text metric. The real
            # set once printed "CER (mean) 55.0000" from exactly one
            # document, in a table whose other rows were over 22 -- the
            # count was two rows further down and nothing tied them
            # together.
            f"| CER (mean) | {_num(head['cer_mean'])} {_n_of(summary)} |",
            f"| CER (p95) | {_num(head['cer_p95'])} {_n_of(summary)} |",
            f"| WER (mean) | {_num(head['wer_mean'])} {_n_of(summary)} |",
            f"| documents with reference text | {summary['documents_with_reference_text']} / {summary['documents']} |",
            f"| latency p50 / p95 per document | {summary['latency']['per_document_s']['p50']}s / "
            f"{summary['latency']['per_document_s']['p95']}s |",
            f"| latency p50 / p95 per page | {summary['latency']['per_page_s']['p50']}s / "
            f"{summary['latency']['per_page_s']['p95']}s |",
            f"| statuses | {json.dumps(summary['status_totals'])} |",
            "",
        ]

        pops = summary.get("by_population") or {}
        if pops:
            lines += [
                "### Printed vs handwritten",
                "",
                "OS-NFR-001's 95% is for *standard document layouts*. A form "
                "filled in by pen is not one, so the two populations are "
                "reported separately and never averaged into the headline "
                "above.",
                "",
                "| population | docs | fields | correct | field accuracy | `extracted` precision |",
                "|---|---|---|---|---|---|",
            ]
            for name in ("printed", "handwritten"):
                p = pops[name]
                lines += [
                    f"| {name} | {p['documents']} | {p['fields_expected']} | "
                    f"{p['fields_correct']} | **{_pct(p['field_accuracy'])}** | "
                    f"{_pct(p['extracted_precision'])} |"
                ]
            lines += [""]

        rf = summary.get("review_flag")
        if rf:
            lines += [
                "### `requires_review` as a routing decision",
                "",
                "| metric | value |",
                "|---|---|",
                f"| documents flagged | {rf['flagged']} / {summary['documents']} |",
                f"| documents that really had a wrong or missing field | "
                f"{rf['documents_with_an_error']} / {summary['documents']} |",
                f"| precision (flagged and really wrong) | {_pct(rf['precision'])} |",
                f"| **recall (really wrong and caught)** | **{_pct(rf['recall'])}** |",
                f"| missed -- wrong but not flagged | {rf['fn']} |",
                "",
                f"Reasons fired: `{json.dumps(rf['reasons'])}`",
                "",
            ]
            if rf["missing_mandatory"]:
                lines += [
                    f"Mandatory fields reported missing: "
                    f"`{json.dumps(rf['missing_mandatory'])}`",
                    "",
                ]

        # A reference that was thrown out is a label to fix, and it has to
        # say so here -- otherwise the only trace is a CER count one lower
        # than the reader expects, which nobody chases.
        unusable = summary.get("unusable_reference_text") or []
        if unusable:
            lines += [
                f"**{len(unusable)} label(s) have an unusable `reference_text`** "
                "-- excluded from CER/WER:",
                "",
            ]
            lines += [f"- {u}" for u in unusable]
            lines += [""]

        ori = (summary.get("preprocessing") or {}).get("orientation") or {}
        if ori.get("pages_classified"):
            # The false-rotation rate leads, because it is the number that
            # decides whether this stage is safe on by default -- rotating
            # an upright page costs as much as correcting a rotated one.
            lines += [
                "### Preprocessing -- orientation",
                "",
                "| metric | value |",
                "|---|---|",
                f"| pages classified | {ori['pages_classified']} "
                f"({ori['pages_native_text']} native-text pages never rasterized, never classified) |",
                f"| upright pages wrongly rotated | **{ori['upright_pages_rotated']} / {ori['upright_pages']}"
                + (f" ({ori['false_rotation_rate']:.2%})** |" if ori.get("false_rotation_rate") is not None else "** |"),
                f"| rotated pages corrected | {ori['rotated_pages_corrected']} / {ori['rotated_pages']}"
                + (f" ({ori['correction_rate']:.2%}) |" if ori.get("correction_rate") is not None else " |"),
                f"| applied angles | {json.dumps(ori['by_applied_angle'])} |",
                "",
            ]
            if ori.get("wrongly_rotated"):
                lines += ["Upright pages that were rotated:", ""]
                lines += [
                    f"- `{w['doc_id']}` page {w['page']}: {w['angle']}° at confidence {w['confidence']}"
                    for w in ori["wrongly_rotated"][:20]
                ]
                lines.append("")

        if len(summary["by_condition"]) > 1:
            lines += [
                "| condition | docs | failed | field exact | CER | WER | p50 s | p95 s |",
                "|---|---|---|---|---|---|---|---|",
            ]
            for name, entry in summary["by_condition"].items():
                lines.append(
                    f"| `{name}` | {entry['docs']} | {entry['failed']} | "
                    f"{entry['field_exact_match']:.1%} | {_num(entry['cer_mean'])} | "
                    f"{_num(entry['wer_mean'])} | {entry['latency_p50']} | {entry['latency_p95']} |"
                )
            lines.append("")

        lines += [
            "| field | expected | produced | correct | precision | recall | extracted-prec |",
            "|---|---|---|---|---|---|---|",
        ]
        for name, entry in summary["fields"].items():
            lines.append(
                f"| `{name}` | {entry['expected']} | {entry['produced']} | {entry['correct']} | "
                f"{entry['precision']:.1%} | {entry['recall']:.1%} | {entry['extracted_precision']:.1%} |"
            )
        lines.append("")

    if report.get("real_problems"):
        lines += ["## Real set -- problems", ""]
        lines += [f"- {p}" for p in report["real_problems"]]
        lines.append("")

    for set_name in (SYNTHETIC, REAL):
        block = ((report.get("sets") or {}).get(set_name) or {}).get("searchable_pdf") or {}
        if not block.get("attempted"):
            continue
        rate = block["success_rate"]
        lines += [
            f"## Searchable PDF rendition -- {set_name}",
            "",
            f"- **{block['succeeded']} of {block['attempted']}** completed documents "
            f"produced one ({rate:.1%})" if rate is not None else "- not measured",
            "",
        ]
        if block["failed"]:
            lines += ["Failure reasons:", ""]
            lines += [f"- {count}x `{reason}`" for reason, count in block["failure_reasons"].items()]
            lines += [""]

    memory = report["memory"]
    lines += [
        "## Memory (whole run, both sets)",
        "",
        f"- peak: **{memory.get('peak_gib')} GiB** (limit {memory.get('limit_gib')} GiB, "
        f"source `{memory.get('peak_source')}`)",
        f"- median {memory.get('median_gib')} GiB, p95 {memory.get('p95_gib')} GiB, "
        f"over {memory.get('samples')} samples at {memory.get('interval_s')}s",
        f"- {memory.get('note')}",
        "",
    ]
    return "\n".join(lines)


# ----------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the OCR evaluation sets.")
    parser.add_argument("--variant", default="baseline", help=f"one of: {', '.join(sorted(VARIANTS))}")
    parser.add_argument("--set", dest="which", default="both", choices=["both", SYNTHETIC, REAL])
    parser.add_argument(
        "--per-condition",
        type=int,
        default=3,
        help="synthetic documents per (type, condition). 3 gives 39 per type, meeting "
             "section 5's >=30. Use 1 for a quick iteration pass.",
    )
    parser.add_argument("--only", default="", help="comma-separated synthetic condition names")
    parser.add_argument("--types", default="", help="comma-separated type codes")
    parser.add_argument("--report-name", default="", help="output basename (default: the variant)")
    parser.add_argument(
        "--no-vendor-seed", action="store_true",
        help="run with an EMPTY known-vendor list -- what a tenant that has "
             "filed nothing yet sees",
    )
    parser.add_argument("--rss-interval-s", type=float, default=2.0)
    args = parser.parse_args(argv)

    # Refuse to measure stale containers. Three runs have already been
    # invalidated by an image older than the source; see eval/staleness.py.
    staleness.enforce(what="eval")

    only = {c.strip() for c in args.only.split(",") if c.strip()}
    unknown = only - set(conditions_mod.BY_NAME)
    if unknown:
        raise SystemExit(f"unknown condition(s): {', '.join(sorted(unknown))}")
    types = {t.strip().upper() for t in args.types.split(",") if t.strip()}
    unknown_types = types - set(schema_mod.TYPE_FIELDS)
    if unknown_types:
        raise SystemExit(f"unknown type(s): {', '.join(sorted(unknown_types))}")

    settings = build_settings(args.variant)
    wants_synthetic = args.which in ("both", SYNTHETIC)
    wants_real = args.which in ("both", REAL)

    documents: list = []
    real_pending: list[str] = []
    real_problems: list[str] = []

    if wants_synthetic:
        print(f"[eval] building synthetic corpus (per_condition={args.per_condition})...", flush=True)
        synthetic_docs = harness.build_corpus(
            per_condition=args.per_condition,
            only_conditions=only or None,
            only_types=types or None,
        )
        print(f"[eval] synthetic: {len(synthetic_docs)} documents", flush=True)
        documents += synthetic_docs

    if wants_real:
        real_docs, real_pending, real_problems = realset.load_real_documents()
        print(
            f"[eval] real: {len(real_docs)} reviewed, {len(real_pending)} awaiting review, "
            f"{len(real_problems)} problem(s)",
            flush=True,
        )
        for problem in real_problems:
            print(f"[eval]   ! {problem}", flush=True)
        documents += real_docs

    if not documents:
        print("[eval] nothing to run.")
        return 1

    session_factory = harness.make_session_factory()
    storage = harness.InMemoryStorage()

    # The tenant's known-vendor list. In production it is built from
    # values a human confirmed in the review screen; the eval has no
    # review history, so it stands in the real set's own labels -- which
    # ARE human-confirmed values, and is also exactly why `--no-vendor-
    # seed` exists. A seeded run measures recognising a vendor already on
    # file; an unseeded one measures a vendor nobody has filed before, and
    # that is the number for a new tenant.
    real_documents = [d for d in documents if d.set_name == REAL]
    known_vendors = [] if args.no_vendor_seed else harness.seed_known_vendors(real_documents)
    if known_vendors:
        print(f"[eval] known-vendor list seeded with {len(known_vendors)} confirmed vendor(s)", flush=True)
    else:
        print("[eval] known-vendor list EMPTY (--no-vendor-seed or no real set)", flush=True)

    print("[eval] warming up models...", flush=True)
    pipeline = harness.build_pipeline(
        settings, session_factory, storage,
        known_vendors_loader=(lambda _tenant_id: known_vendors) if known_vendors else None,
    )

    results = []
    started = time.monotonic()
    # A per-document progress trail, flushed as each one finishes.
    #
    # The aggregate report is only written at the very end, so a run that
    # is interrupted at document 116 of 117 produces nothing at all --
    # which happened: an hour of inference was nearly lost to a shell
    # being killed. This file means an interrupted run still says which
    # documents were scored and how, and a killed run can be reasoned
    # about instead of merely repeated.
    report_name = args.report_name or args.variant
    progress_path = REPORTS_DIR / f"{report_name}.progress.jsonl"
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    progress_path.write_text("", encoding="utf-8")

    # The sampler starts *after* warmup so the peak reflects the run, not
    # the one-off cost of loading models.
    with rss.MemorySampler(interval_s=args.rss_interval_s) as sampler:
        for n, doc in enumerate(documents, start=1):
            result = harness.run_document(pipeline, session_factory, storage, doc)
            results.append(result)
            with progress_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps({
                    "n": n,
                    "of": len(documents),
                    "set": doc.set_name,
                    "doc_id": doc.doc_id,
                    "condition": doc.condition,
                    "status": result.status,
                    "elapsed_s": round(result.elapsed_s, 3),
                    "error": result.error,
                    "routing": [
                        {"page": p.get("page_number"), "method": p.get("method"),
                         "route": (p.get("routing") or {}).get("route"),
                         "reason": (p.get("routing") or {}).get("reason")}
                        for p in ((result.output or {}).get("pages") or [])
                    ],
                }) + "\n")
                fh.flush()
            print(
                f"[eval] {n}/{len(documents)} [{doc.set_name}] {doc.doc_id} -> {result.status} "
                f"{result.elapsed_s:.1f}s" + (f" ({result.error})" if result.error else ""),
                flush=True,
            )
        memory = sampler.summary()
    wall_clock = time.monotonic() - started

    sets: dict[str, dict] = {}
    rows_by_set: dict[str, list[dict]] = {}
    for set_name in (SYNTHETIC, REAL):
        if set_name == SYNTHETIC and not wants_synthetic:
            continue
        if set_name == REAL and not wants_real:
            continue
        set_results = [r for r in results if r.set_name == set_name]
        set_documents = [d for d in documents if d.set_name == set_name]
        summary, rows = score_set(set_name, set_results, set_documents)
        sets[set_name] = summary
        rows_by_set[set_name] = rows

    report = {
        "variant": args.variant,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "settings": settings_fingerprint(settings),
        "wall_clock_s": round(wall_clock, 1),
        "memory": memory,
        "sets": sets,
        "real_pending": real_pending,
        "real_problems": real_problems,
        # Synthetic rows are safe to commit; real rows are not (see the
        # module docstring) and live in the gitignored detail file.
        "documents": rows_by_set.get(SYNTHETIC, []),
    }

    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    name = report_name
    json_path = REPORTS_DIR / f"{name}.json"
    md_path = REPORTS_DIR / f"{name}.md"
    json_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    written = [json_path, md_path]

    real_rows = rows_by_set.get(REAL) or []
    if real_rows:
        detail_path = REPORTS_DIR / f"{name}-real-detail.json"
        detail_path.write_text(
            json.dumps({"variant": args.variant, "documents": real_rows}, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        written.append(detail_path)

    print("")
    for set_name in (REAL, SYNTHETIC):
        summary = sets.get(set_name)
        if summary is None:
            continue
        head = summary["headline"]
        label = "REAL     " if set_name == REAL else "SYNTHETIC"
        if summary["documents"] == 0:
            print(f"[eval] {label} no documents")
            continue
        print(
            f"[eval] {label} n={summary['documents']:<4} "
            f"field_accuracy={_pct(head['field_accuracy'])}  "
            f"extracted_prec={_pct(head['extracted_precision'])}  "
            f"cer={_num(head['cer_mean'])}"
        )
        rendition = summary.get("searchable_pdf") or {}
        if rendition.get("attempted"):
            rate = rendition["success_rate"]
            note = "" if rendition["failed"] == 0 else "   <-- RENDITION FAILURES"
            print(
                f"[eval] {label} searchable_pdf {rendition['succeeded']}/"
                f"{rendition['attempted']} ({_pct(rate)}){note}"
            )
            for reason, count in list(rendition["failure_reasons"].items())[:3]:
                print(f"[eval]           {count:>4}x {reason}")
    if not (sets.get(REAL) or {}).get("documents"):
        print("[eval] NOTE: no real data -- a synthetic result can veto a change, never justify one.")
    print(f"[eval] peak memory {memory.get('peak_gib')} GiB")
    print("[eval] wrote " + ", ".join(str(p) for p in written))
    return 0


if __name__ == "__main__":
    sys.exit(main())
