"""Staging and execution: build the corpus, drive the real OcrPipeline.

The pipeline is driven through its real `process_version` entry point --
claim, run, commit -- not through a private helper, so the harness
measures the same code path a queued upload takes. What it swaps out is
only the *infrastructure* around it: an in-memory SQLite session (the
pattern from tests/conftest.py) and an in-memory object store, so a run
needs no Postgres, no MinIO and no RabbitMQ and therefore no rebuild.

The engines are real. `LayoutEngine` and `PaddleOcrEngine` are the
production classes with production models; substituting fakes here would
make every accuracy number meaningless.
"""

from __future__ import annotations

import json
import random
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from ocr_worker.config import Settings
from ocr_worker.extraction import FieldSpec
from ocr_worker.layout import LayoutEngine
from ocr_worker.models import (
    Base,
    Document,
    DocumentOcrFieldResult,
    DocumentType,
    DocumentVersion,
)
from ocr_worker.ocr_engine import PaddleOcrEngine
from ocr_worker.orientation import NullOrientationClassifier, PaddleDocOrientationClassifier
from ocr_worker.pipeline import OcrPipeline
from ocr_worker.repository import OcrRepository
from ocr_worker.db import session_scope

from . import conditions as conditions_mod
from . import corpus as corpus_mod
from . import schema as schema_mod


# ----------------------------------------------------------------------
# Infrastructure doubles
# ----------------------------------------------------------------------
class InMemoryStorage:
    """Stands in for `DocumentContentClient`.

    Only the three methods the pipeline actually calls are implemented;
    anything else is a genuine error rather than a silent no-op, so a
    future pipeline change that starts touching storage differently fails
    loudly here instead of being quietly unmeasured.
    """

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.outputs: dict[str, dict] = {}
        self.renditions: dict[str, bytes] = {}

    def get_object_bytes(self, object_key: str) -> bytes:
        return self.objects[object_key]

    def put_source_object(self, object_key: str, data: bytes, content_type: str) -> str:
        self.objects[object_key] = data
        return object_key

    def put_ocr_output_json(self, object_key: str, data: dict) -> str:
        self.outputs[object_key] = data
        return object_key

    def put_rendition(self, object_key: str, data: bytes, content_type: str = "application/pdf") -> str:
        """Searchable PDF rendition (production prompt 3.11).

        Its absence made every eval run fail every rendition -- silently,
        until the report started counting them. The class docstring above
        predicted exactly this: "a future pipeline change that starts
        touching storage differently fails loudly here instead of being
        quietly unmeasured". It did fail loudly; nothing was reading the
        failure.
        """
        self.renditions[object_key] = data
        return object_key

    def ensure_buckets(self) -> None:
        return None


def make_session_factory():
    """StaticPool + check_same_thread=False, for the same reason
    tests/conftest.py uses it: the hard-timeout watchdog writes its
    failure record from its own thread, and SQLite's default pool hands
    each thread a private (empty) `:memory:` database."""
    engine = create_engine(
        "sqlite:///:memory:", poolclass=StaticPool, connect_args={"check_same_thread": False}
    )
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)


# ----------------------------------------------------------------------
# Corpus construction
# ----------------------------------------------------------------------
CORPUS_DIR = Path(__file__).resolve().parent / "corpus"
LABELS_DIR = Path(__file__).resolve().parent / "labels"


def build_corpus(
    *,
    per_condition: int,
    only_conditions: set[str] | None = None,
    only_types: set[str] | None = None,
    corpus_dir: Path | None = None,
    write_labels: bool = True,
) -> list[corpus_mod.CorpusDocument]:
    """Materialize the corpus to disk, deterministically.

    Two design points that are easy to get wrong and expensive to notice
    later:

    **One source document per (type, replica), rendered under every
    condition.** The seed deliberately excludes the condition name, so
    `invoice-native_pdf-00` and `invoice-rotated_90-00` are the *same*
    invoice with the same invoice number, dates and totals. Seeding per
    condition instead would mean every cross-condition comparison was
    confounded by the documents also being different, and "rotation costs
    us 40 points of accuracy" could not be separated from "that happened
    to be a harder invoice".

    **On disk, not in memory.** A 300-DPI scan condition is ~26 MB; a
    117-document corpus would be ~700 MB resident before any OCR runs.
    Files are written once and re-read per document.
    """
    corpus_dir = corpus_dir or CORPUS_DIR
    corpus_dir.mkdir(parents=True, exist_ok=True)
    if write_labels:
        LABELS_DIR.mkdir(parents=True, exist_ok=True)

    documents: list[corpus_mod.CorpusDocument] = []
    for type_code in ("INVOICE", "CONTRACT", "POLICY"):
        if only_types and type_code not in only_types:
            continue
        for replica in range(per_condition):
            source_key = f"{type_code.lower()}-{replica:02d}"
            index = corpus_mod.stable_index(source_key)

            # Built once per (type, replica) and reused by every
            # condition, including the multi-page variant which needs its
            # own page count and therefore its own render.
            base_cache: dict[int, tuple[bytes, dict, str]] = {}

            for condition in conditions_mod.CONDITIONS:
                if only_conditions and condition.name not in only_conditions:
                    continue
                if condition.page_count not in base_cache:
                    rng = random.Random(f"{corpus_mod.SEED}:{source_key}")
                    base_cache[condition.page_count] = corpus_mod.build_base_pdf(
                        type_code, index, rng, page_count=condition.page_count
                    )
                pdf_bytes, fields, reference_text = base_cache[condition.page_count]

                doc_id = f"{type_code.lower()}-{condition.name}-{replica:02d}"
                path = corpus_dir / f"{doc_id}.{condition.extension}"
                if not path.exists():
                    path.write_bytes(condition.apply(pdf_bytes, index))

                document = corpus_mod.CorpusDocument(
                    doc_id=doc_id,
                    type_code=type_code,
                    condition=condition.name,
                    fields=fields,
                    reference_text=reference_text,
                    filename=path.name,
                    mime_type=condition.mime_type,
                    path=path,
                    degraded=condition.degraded,
                    expected_page_count=condition.page_count,
                )
                if write_labels:
                    (LABELS_DIR / f"{doc_id}.json").write_text(
                        json.dumps(document.label_dict(), indent=2), encoding="utf-8"
                    )
                documents.append(document)
    return documents


# ----------------------------------------------------------------------
# Running one document
# ----------------------------------------------------------------------
@dataclass
class RunResult:
    doc_id: str
    type_code: str
    condition: str
    set_name: str
    status: str
    elapsed_s: float
    page_count: int
    error: str | None = None
    output: dict | None = None
    # field_key -> "extracted" | "low_confidence" | "not_found".
    #
    # Read from `document_ocr_field_results`, not from the output JSON:
    # `requested_fields_to_output_dict` emits only value/confidence/bbox,
    # so the per-field status exists *solely* in that table. Scoring
    # section 5's "extracted-status precision" off the output dict
    # reported 0% for every field -- not because the pipeline was
    # unconfident, but because the harness was reading somewhere the
    # status was never written.
    field_statuses: dict[str, str] = None  # type: ignore[assignment]


def stage_version(session_factory, storage: InMemoryStorage, doc: corpus_mod.CorpusDocument) -> uuid.UUID:
    """Insert the tenant/type/document/version rows and the queued job.

    Mirrors what dms-core's `uploadDocument` writes, including the queued
    `processing_jobs` row -- `process_version` claims that row with a
    compare-and-swap and returns "skipped" if it is missing, so omitting
    it would make every document silently skip rather than fail.
    """
    tenant_id = uuid.uuid4()
    document_type_id = uuid.uuid4()
    document_id = uuid.uuid4()
    version_id = uuid.uuid4()
    object_key = f"tenants/{tenant_id}/documents/{document_id}/versions/{version_id}/{doc.filename}"
    storage.objects[object_key] = doc.read()

    with session_factory() as session:
        session.add(
            DocumentType(
                document_type_id=document_type_id,
                tenant_id=tenant_id,
                type_name=doc.type_code.lower(),
            )
        )
        session.add(
            Document(
                document_id=document_id,
                tenant_id=tenant_id,
                document_type_id=document_type_id,
                title=doc.doc_id,
            )
        )
        session.add(
            DocumentVersion(
                version_id=version_id,
                document_id=document_id,
                tenant_id=tenant_id,
                storage_object_key=object_key,
                original_filename=doc.filename,
                mime_type=doc.mime_type,
                scan_status="clean",
                ocr_status="pending",
            )
        )
        session.commit()

    with session_scope(session_factory) as session:
        OcrRepository(session).ensure_queued_job(
            tenant_id=tenant_id, version_id=version_id, correlation_id=None
        )
    return version_id


def build_pipeline(
    settings: Settings, session_factory, storage: InMemoryStorage,
    known_vendors_loader=None,
) -> OcrPipeline:
    layout_engine = LayoutEngine()
    ocr_engine = PaddleOcrEngine()
    layout_engine.warm_up()
    ocr_engine.warm_up(lang=settings.ocr_languages)
    # Mirrors worker_pool.py's pool-worker initializer exactly, including
    # the warm-up: the whole point of this harness is to drive what
    # production drives, so a preprocessing stage that production has and
    # this does not would make every measurement here a measurement of
    # something else.
    orientation_classifier = (
        PaddleDocOrientationClassifier()
        if settings.ocr_orientation_enabled
        else NullOrientationClassifier()
    )
    orientation_classifier.warm_up()
    return OcrPipeline(
        settings=settings,
        session_factory=session_factory,
        storage=storage,
        layout_engine=layout_engine,
        ocr_engine=ocr_engine,
        orientation_classifier=orientation_classifier,
        known_vendors_loader=known_vendors_loader,
    )


def run_raw_document(
    pipeline: OcrPipeline,
    session_factory,
    storage: InMemoryStorage,
    *,
    path: Path,
    mime_type: str,
    specs,
) -> RunResult:
    """Run one file that has no label yet -- the label drafter's entry point.

    Shares `run_document`'s staging and teardown exactly, so a draft is
    produced by the same code path that will later score the document.
    A draft produced by a different path could disagree with the run it
    is meant to pre-fill.
    """
    placeholder = corpus_mod.CorpusDocument(
        doc_id=path.stem,
        type_code="INVOICE",  # unused: specs are passed explicitly below
        condition="real",
        fields={},
        reference_text="",
        filename=path.name,
        mime_type=mime_type,
        path=path,
        set_name="real",
    )
    return _run(pipeline, session_factory, storage, placeholder, specs)


def run_document(
    pipeline: OcrPipeline,
    session_factory,
    storage: InMemoryStorage,
    doc: corpus_mod.CorpusDocument,
) -> RunResult:
    specs = [
        # `is_mandatory` is carried, not dropped. Without it `pipeline`'s
        # `mandatory_keys` is empty, which silently disables the FIRST of
        # `requires_review`'s three arms ("any mandatory field not
        # extracted") for every eval run -- so the review flag was being
        # scored with a third of its inputs switched off, and a document
        # with no amount, no currency and no invoice number came back
        # `requires_review: false`. `eval/schema.py` has carried the flag
        # all along for exactly this; only this call site dropped it.
        FieldSpec(
            field_key=s.field_key,
            data_type=s.data_type,
            options=s.options,
            is_mandatory=s.is_mandatory,
            ocr_extractable=s.ocr_extractable,
        )
        for s in schema_mod.field_specs_for(doc.type_code)
    ]
    return _run(pipeline, session_factory, storage, doc, specs)


def seed_known_vendors(documents, *, exclude_doc_id: str | None = None) -> list[str]:
    """The tenant's known-vendor list as it would stand when `exclude_doc_id`
    arrives -- i.e. built from every OTHER document's confirmed vendor.

    **Leave-one-out, and it has to be.** In production the list is built
    from vendors a human already accepted or corrected in the review
    screen, so a document is always scored against a list that does not
    contain its own answer. Seeding from the whole set instead would hand
    the matcher the very label it is about to be graded on, and the number
    that came back would be "can it recognise a string it was just given",
    which is not a question anyone needs answered.

    It is not a theoretical concern. Whole-set seeding scored 83.9% here
    against 86.0% unseeded, and both figures were meaningless: the gains
    were self-matches and the losses were one vendor spelled two ways
    across different documents.

    A document whose vendor appears nowhere else is therefore scored with
    that vendor absent from the list -- which is exactly right, because
    the first invoice from a new supplier really does arrive before anyone
    has confirmed its name.
    """
    seen: dict[str, str] = {}
    for doc in documents:
        if exclude_doc_id is not None and doc.doc_id == exclude_doc_id:
            continue
        name = (doc.fields or {}).get("vendor_supplier_name")
        if isinstance(name, str) and name.strip():
            seen.setdefault(name.strip().casefold(), name.strip())
    return sorted(seen.values())


def _run(
    pipeline: OcrPipeline,
    session_factory,
    storage: InMemoryStorage,
    doc: corpus_mod.CorpusDocument,
    specs,
) -> RunResult:
    version_id = stage_version(session_factory, storage, doc)

    started = time.monotonic()
    try:
        outcome = pipeline.process_version(version_id, requested_fields=specs)
        status, error, output = outcome.status, outcome.error, outcome.output
    except Exception as exc:  # a crash is a result, not a reason to abandon the run
        status, error, output = "crashed", f"{type(exc).__name__}: {exc}", None
    elapsed = time.monotonic() - started

    with session_factory() as session:
        rows = (
            session.query(DocumentOcrFieldResult)
            .filter(DocumentOcrFieldResult.version_id == version_id)
            .all()
        )
        field_statuses = {row.field_key: row.status for row in rows}

    # Drop this document's source bytes and stored output before the next
    # one is staged. Without this the in-memory store grows monotonically
    # -- ~26 MB for a single 300-DPI scan, so a full run would carry
    # several hundred MB of already-processed documents alongside the
    # models, and the peak-RSS number the run exists to produce would be
    # measuring the harness rather than the pipeline.
    storage.objects.clear()
    storage.outputs.clear()

    pages = len((output or {}).get("pages") or [])
    return RunResult(
        doc_id=doc.doc_id,
        type_code=doc.type_code,
        condition=doc.condition,
        set_name=doc.set_name,
        status=status,
        elapsed_s=elapsed,
        page_count=pages,
        error=error,
        output=output,
        field_statuses=field_statuses,
    )
