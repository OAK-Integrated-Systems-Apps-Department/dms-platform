"""`python -m eval.draft_labels` -- pre-fill label files for real documents.

Drops a draft `eval/labels/real/<doc_id>.json` next to every unlabelled
document in `eval/corpus/real/`, pre-filled with what the pipeline
currently extracts, so correcting a label is a matter of fixing a few
values rather than typing all of them.

**What a draft is not.** It is the pipeline's own output. Scoring against
it would compare the pipeline to itself and report perfect accuracy
forever. Every draft is therefore written with `"reviewed": false`, and
`eval.run` refuses to score an unreviewed document -- it counts it as
pending instead. The drafter never overwrites an existing label file,
reviewed or not: a corrected label is the most expensive artifact in this
harness and a stray re-run must not be able to destroy one.

The type guess is a guess. It decides which fields are asked for, so a
wrong `type_code` makes every field in the draft wrong -- it is the first
thing to check.
"""

from __future__ import annotations

import argparse
import sys

from . import harness, realset, schema as schema_mod


def _guess_type_code(values: dict) -> str:
    """Cheapest signal that beats a coin flip, and nothing more.

    Deliberately crude: a confident-looking classifier here would invite
    someone to trust the draft's `type_code` instead of checking it, and
    the whole point of a draft is that a human checks it.
    """
    if values.get("invoice_number") or values.get("amount"):
        return "INVOICE"
    if values.get("contract_party") or values.get("contract_value"):
        return "CONTRACT"
    if values.get("effective_date") or values.get("review_due_date"):
        return "POLICY"
    return "INVOICE"


def _union_field_specs():
    """Every field across all three seeded types, asked for at once.

    The type is not known until after extraction, so the drafting pass
    asks for everything and lets the guess follow from what came back.
    """
    from ocr_worker.extraction import FieldSpec

    seen: dict[str, FieldSpec] = {}
    for type_code in schema_mod.TYPE_FIELDS:
        for spec in schema_mod.field_specs_for(type_code):
            seen.setdefault(
                spec.field_key,
                FieldSpec(field_key=spec.field_key, data_type=spec.data_type, options=spec.options),
            )
    return list(seen.values())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Draft label files for real evaluation documents.")
    parser.add_argument("--only", default="", help="comma-separated doc_ids to draft")
    args = parser.parse_args(argv)
    only = {v.strip() for v in args.only.split(",") if v.strip()}

    supported, unsupported = realset.discover()
    if not realset.REAL_CORPUS_DIR.is_dir():
        realset.REAL_CORPUS_DIR.mkdir(parents=True, exist_ok=True)
        print(f"[draft] created {realset.REAL_CORPUS_DIR} -- put real documents there and re-run.")
        return 0

    for path in unsupported:
        print(f"[draft] SKIP {path.name}: unsupported file type")

    todo = [
        path for path in supported
        if not realset.label_path_for(path).exists()
        and (not only or realset.doc_id_for(path) in only)
    ]
    if not todo:
        print(f"[draft] nothing to do -- {len(supported)} document(s), all already labelled.")
        return 0

    from ocr_worker.config import Settings

    settings = Settings()
    session_factory = harness.make_session_factory()
    storage = harness.InMemoryStorage()
    print(f"[draft] warming up models for {len(todo)} document(s)...", flush=True)
    pipeline = harness.build_pipeline(settings, session_factory, storage)
    specs = _union_field_specs()

    for n, path in enumerate(todo, start=1):
        doc_id = realset.doc_id_for(path)
        result = harness.run_raw_document(
            pipeline, session_factory, storage,
            path=path, mime_type=realset.mime_for(path), specs=specs,
        )
        output = result.output or {}
        raw_fields = output.get("fields") or {}
        values = {
            key: entry.get("value")
            for key, entry in raw_fields.items()
            if isinstance(entry, dict) and entry.get("value") not in (None, "")
        }
        notes = {
            key: {
                "confidence": (raw_fields[key] or {}).get("confidence"),
                "status": (result.field_statuses or {}).get(key),
            }
            for key in values
        }
        type_code = _guess_type_code(values)
        # Keep only the guessed type's own fields, in schema order, so the
        # draft reads like the schema the document will be scored against.
        ordered = {
            key: values[key]
            for key, _mandatory in schema_mod.TYPE_FIELDS[type_code]
            if key in values
        }

        target = realset.write_draft_label(
            path,
            type_code=type_code,
            fields=ordered,
            field_notes={k: notes[k] for k in ordered},
            page_count=result.page_count or 1,
        )
        print(
            f"[draft] {n}/{len(todo)} {doc_id}: guessed {type_code}, "
            f"{len(ordered)} field(s), status={result.status} -> {target.name}",
            flush=True,
        )

    print("")
    print(f"[draft] wrote {len(todo)} draft label(s) to {realset.REAL_LABELS_DIR}")
    print("[draft] Correct each one against the document, then set \"reviewed\": true.")
    print("[draft] Unreviewed documents are reported as pending and are NOT scored.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
