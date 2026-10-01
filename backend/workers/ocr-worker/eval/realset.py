"""The real-document evaluation set.

The synthetic corpus is a regression set: it is reproducible, it covers
the structural failure modes deliberately, and it can veto a change that
breaks something. It is **not** evidence about real-world accuracy,
because it is rendered from vector text by the same process that labels
it. Only real documents answer OS-NFR-001.

Real documents live in `eval/corpus/real/` and their ground truth in
`eval/labels/real/`. Both are gitignored: an invoice carries customer
names, bank details and amounts, and a label file carries exactly the
same values in a more convenient form. Neither belongs in the repository.

**A draft label is not ground truth.** `python -m eval.draft_labels`
fills a label file with what the pipeline currently extracts, which makes
correcting it a matter of fixing a few fields rather than typing all of
them -- but a label the pipeline wrote is a label the pipeline agrees
with by construction, and scoring against it would measure nothing at
all. Every draft carries `"reviewed": false`, and
**an unreviewed document is never scored**; it is reported as pending.
"""

from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path

from . import corpus as corpus_mod
from . import schema as schema_mod

_EVAL_DIR = Path(__file__).resolve().parent
REAL_CORPUS_DIR = _EVAL_DIR / "corpus" / "real"
REAL_LABELS_DIR = _EVAL_DIR / "labels" / "real"

# What the worker can actually ingest today (layout.SUPPORTED_MIME_TYPES'
# own extensions). Anything else in the drop directory is reported rather
# than silently skipped, so a file that was meant to be evaluated does not
# just vanish from the run.
_EXTENSION_MIME = {
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".tif": "image/tiff",
    ".tiff": "image/tiff",
    ".bmp": "image/bmp",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".heic": "image/heic",
    ".heif": "image/heif",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}


def doc_id_for(path: Path) -> str:
    """A filesystem-safe id derived from the filename.

    Deliberately derived from the name rather than from content: the
    person correcting a label needs to see which of their documents it
    belongs to. The id appears in the committed aggregate report, so it
    is also the reason that report redacts values -- see `run.py`.
    """
    stem = unicodedata.normalize("NFKC", path.stem)
    # Leading/trailing dots and underscores are stripped as well as
    # hyphens, not for tidiness: `label_path_for` turns this id straight
    # into a filename, and `.`/`..` are directory references, so a file
    # literally named `....pdf` would otherwise produce the id `...`.
    return re.sub(r"[^A-Za-z0-9._-]+", "-", stem).strip("-._") or "document"


def label_path_for(path: Path) -> Path:
    return REAL_LABELS_DIR / f"{doc_id_for(path)}.json"


def discover() -> tuple[list[Path], list[Path]]:
    """Returns (supported, unsupported) files in the real drop directory."""
    if not REAL_CORPUS_DIR.is_dir():
        return [], []
    supported, unsupported = [], []
    for path in sorted(REAL_CORPUS_DIR.rglob("*")):
        if not path.is_file() or path.name.startswith("."):
            continue
        (supported if path.suffix.lower() in _EXTENSION_MIME else unsupported).append(path)
    return supported, unsupported


def mime_for(path: Path) -> str:
    return _EXTENSION_MIME[path.suffix.lower()]


# ----------------------------------------------------------------------
# Labels
# ----------------------------------------------------------------------
def write_draft_label(
    path: Path,
    *,
    type_code: str,
    fields: dict,
    field_notes: dict,
    page_count: int,
) -> Path:
    """Write a draft label for a real document, for a human to correct.

    Never overwrites a file that already exists -- a corrected label is
    the most expensive artifact in this whole harness and a re-run of the
    drafter must not be able to destroy one.
    """
    REAL_LABELS_DIR.mkdir(parents=True, exist_ok=True)
    target = label_path_for(path)
    if target.exists():
        return target

    label = {
        "_README": [
            "DRAFT -- these values were produced by the OCR pipeline itself and are",
            "NOT ground truth. Correct every field against the document, delete any",
            "field the document genuinely does not contain, then set reviewed: true.",
            "Until reviewed is true this document is reported as pending and is NOT",
            "scored -- scoring against the pipeline's own output would measure",
            "nothing.",
            "",
            "type_code must be one of INVOICE, CONTRACT, POLICY. It decides which",
            "fields are asked for, so a wrong one makes every field here wrong.",
            "",
            "reference_text is optional. Leave it empty unless you want CER/WER for",
            "this document, in which case paste the document's full text.",
            "",
            "Formats: dates YYYY-MM-DD; amounts digits only (1234.50, no symbol, no",
            "commas) with the currency in its own field as a code; multi_select fields",
            "(tags, keywords) are JSON lists. Run `python -m eval.check_labels` when",
            "you are done -- it reports malformed values and never edits anything.",
            "",
            "line_items is optional and not scored yet. Delete the key if the document",
            "has none.",
        ],
        "reviewed": False,
        "doc_id": doc_id_for(path),
        "filename": path.name,
        "type_code": type_code,
        "mime_type": mime_for(path),
        "expected_page_count": page_count,
        # Real documents carry no known degradation, so they never feed
        # the quality-flag calibration the synthetic conditions do.
        "degraded": None,
        "reference_text": "",
        "fields": fields,
        # Optional, and nothing scores it yet. Present because a reviewer
        # typing an invoice's lines while the document is already open in
        # front of them is far cheaper than a second pass over every
        # document later, and `eval.check_labels` validates them now, so
        # what gets captured is at least well-formed. Delete the key
        # entirely for a document with no line items.
        "line_items": [],
        "_draft_notes": field_notes,
    }
    target.write_text(json.dumps(label, indent=2, ensure_ascii=False), encoding="utf-8")
    return target


def load_labels() -> tuple[list[dict], list[str]]:
    """Returns (reviewed labels, doc_ids awaiting review)."""
    if not REAL_LABELS_DIR.is_dir():
        return [], []
    reviewed, pending = [], []
    for path in sorted(REAL_LABELS_DIR.glob("*.json")):
        label = json.loads(path.read_text(encoding="utf-8"))
        if label.get("reviewed") is True:
            reviewed.append(label)
        else:
            pending.append(label.get("doc_id", path.stem))
    return reviewed, pending


def load_real_documents() -> tuple[list[corpus_mod.CorpusDocument], list[str], list[str]]:
    """Returns (scoreable documents, pending doc_ids, problems).

    A label with no matching file, or a file with no label, is a problem
    reported by name rather than a document quietly missing from the run.
    """
    supported, unsupported = discover()
    by_id = {doc_id_for(p): p for p in supported}
    reviewed, pending = load_labels()

    problems = [f"{p.name}: unsupported file type" for p in unsupported]
    documents: list[corpus_mod.CorpusDocument] = []

    for label in reviewed:
        doc_id = label.get("doc_id")
        path = by_id.get(doc_id)
        if path is None:
            problems.append(f"{doc_id}: reviewed label has no matching file in corpus/real/")
            continue
        type_code = (label.get("type_code") or "").upper()
        if type_code not in schema_mod.TYPE_FIELDS:
            problems.append(
                f"{doc_id}: type_code {type_code!r} is not one of "
                f"{', '.join(sorted(schema_mod.TYPE_FIELDS))}"
            )
            continue
        documents.append(
            corpus_mod.CorpusDocument(
                doc_id=doc_id,
                type_code=type_code,
                # Every real document is its own condition -- there is no
                # degradation matrix here, and pretending otherwise would
                # put real results into synthetic per-condition buckets.
                condition="real",
                fields=label.get("fields") or {},
                reference_text=label.get("reference_text") or "",
                filename=label.get("filename") or path.name,
                mime_type=label.get("mime_type") or mime_for(path),
                path=path,
                degraded=bool(label.get("degraded")),
                expected_page_count=int(label.get("expected_page_count") or 1),
                set_name="real",
            )
        )

    labelled = {label.get("doc_id") for label in reviewed} | set(pending)
    for doc_id, path in by_id.items():
        if doc_id not in labelled:
            problems.append(f"{path.name}: no label -- run `python -m eval.draft_labels`")

    return documents, pending, problems
