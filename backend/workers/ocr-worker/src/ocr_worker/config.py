from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict

DMS_EVENTS_EXCHANGE = "oak.dms.events"
DMS_EVENTS_DLX = "oak.dms.events.dlx"

ROUTING_KEY_SCAN_COMPLETED = "document.version.scan.completed"
ROUTING_KEY_OCR_REQUESTED = "document.version.ocr.requested"
# Bulk import's OCR. Same message shape, separate routing key and separate
# queue, because the two have different urgency: someone watching a scan
# upload is waiting for the result, while a 5,000-file import is not. One
# shared queue would make the import's files arrive first and the
# interactive one wait behind all of them -- head-of-line blocking that no
# amount of prefetch tuning fixes, because it is the queue order itself.
ROUTING_KEY_OCR_REQUESTED_BULK = "document.version.ocr.requested.bulk"
ROUTING_KEY_OCR_COMPLETED = "document.version.ocr.completed"
ROUTING_KEY_OCR_FAILED = "document.version.ocr.failed"
ROUTING_KEY_OCR_DEAD_LETTER = "document.version.ocr.dead-letter"

QUEUE_SCAN_COMPLETED = "ocr-worker.q.scan-completed"
QUEUE_OCR_REQUESTED = "ocr-worker.q.ocr-requested"
QUEUE_OCR_REQUESTED_BULK = "ocr-worker.q.ocr-requested-bulk"
QUEUE_DEAD_LETTER = "ocr-worker.q.dead-letter"

# Exit status a pool worker uses when its own hard per-document watchdog
# kills it (pipeline.py's `_on_hard_timeout`). Distinct from every other way
# a pool worker can die so the parent can tell "this document timed out and
# the failure row is ALREADY written" apart from "this worker was OOM-killed
# / crashed and the reaper still has to reclaim the lease" -- pebble surfaces
# both as ProcessExpired, differing only in `.exitcode`. 69 is arbitrary but
# outside the 0-2 range a normal interpreter exit uses and outside the
# 128+signal range a signal-kill produces.
OCR_TIMEOUT_EXIT_CODE = 69


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    app_name: str = "ocr-worker"
    log_level: str = "INFO"

    # -- Network posture --
    # This worker is the only place a document's *content* is ever read,
    # and it is a deliberate design constraint that content never leaves
    # it: there is no LLM tier and no remote inference of any kind (see
    # README "No LLM, by design"). Every model runs locally on CPU.
    #
    # One outbound call survives that rule and it is worth naming rather
    # than hiding. On its first `DocumentConverter` construction, Docling
    # resolves each model's pinned revision against huggingface.co --
    # `GET /api/models/docling-project/docling-layout-heron/revision/main`
    # -- even when the weights are already cached. It sends no document
    # content, only the model name. It is a cache revalidation.
    #
    # Setting this true exports HF_HUB_OFFLINE/TRANSFORMERS_OFFLINE before
    # any model loads, which removes even that call: verified by running a
    # full conversion under `docker run --network none`, which succeeds
    # from the mounted cache alone.
    #
    # **Default TRUE since the weights were baked into the image.** The
    # reason it used to default false no longer holds: the caches were
    # named volumes populated on first use, so enabling this before the
    # first warm-up left a fresh deployment unable to fetch weights at
    # all. The Dockerfile's prefetch stage now bakes all ~653 MB, so a
    # fresh container already has them and the revalidation call buys
    # nothing.
    #
    # Verified on an `--internal` Docker network with no model volumes:
    # `/readyz` 503 -> 200 in 5s, and a 21-document real-set run matched
    # the networked baseline field for field.
    #
    # Set it false only when deliberately running a language that was NOT
    # baked (see the Dockerfile's `OCR_PREFETCH_LANGS`), which needs the
    # outbound fetch this flag suppresses.
    ocr_offline_models: bool = True

    # -- /fingerprint (development aid) --
    # Reports md5s of the source this process actually loaded, so a
    # verification harness can refuse to measure a stale container --
    # three runs have been invalidated that way, none of them visibly.
    # The obvious check (`docker exec` + `md5sum`) is unavailable from
    # inside a container, which is where every harness here runs, so it
    # is served over HTTP instead.
    #
    # Requires `X-Internal-Token` like every other /ocr route. Default ON
    # because dev is where it earns its keep; **turn it OFF in
    # production** -- it enumerates this service's internal module names,
    # and nothing in production asks for it. Disabled returns 404 rather
    # than 403, so a disabled deployment does not confirm it exists.
    ocr_fingerprint_enabled: bool = True

    # -- Postgres (same instance/database as dms-core; see README for the
    # least-privilege follow-up noted there) --
    db_url: str = "postgresql+psycopg://ocr_worker_user:ocr_worker_pass@localhost:5433/dms_core_db"
    db_pool_size: int = 5

    # -- RabbitMQ --
    rabbitmq_url: str = "amqp://dms:changeme@localhost:5672/"
    # Left unset (the default), prefetch tracks `ocr_worker_processes`
    # exactly -- see `effective_prefetch_count` below for why holding more
    # unacked deliveries than there are pool workers to run them is wrong.
    # An explicit PREFETCH_COUNT still overrides it.
    prefetch_count: int | None = None
    max_delivery_attempts: int = 5

    # -- Process pool (Phase 1 stability -- see docs/ocr/failure-analysis.md
    # #2: one shared PaddleOCR/Docling instance across FastAPI's threadpool
    # AND the consumer's ThreadPoolExecutor caused real, measured contention
    # under concurrency (2.7x slowdown at just 4 concurrent requests)) --
    ocr_worker_processes: int = 2
    # Bounds memory growth from any per-call native-library leak over the
    # container's lifetime; each pool worker is recycled after this many
    # documents.
    ocr_worker_max_tasks_per_process: int = 50
    # Real problem, real fix (see docs/ocr/failure-analysis.md #2/B.2 in the
    # implementation plan): PaddleX defaults to PADDLE_PDX_CPU_NUM_THREADS=10
    # per inference call if unset -- on a 4-core container, every concurrent
    # OCR call already wants 10 threads for itself. threads_per_process =
    # cpu_count // ocr_worker_processes is derived at startup (ocr_engine.py/
    # main.py), not configured directly here.

    # -- Per-document timeout, scaled by page count (see
    # docs/ocr/failure-analysis.md #1: a single fixed timeout is wrong for
    # both a 1-page and a 50-page document).
    #
    #     budget = ocr_timeout_base_s + page_count * ocr_timeout_per_page_s
    #
    # Since Phase 1 close-out this budget is computed from the document's
    # REAL page count, read once the file is actually opened inside the
    # pool worker (pipeline.py's DocumentDeadline) -- not from
    # ocr_max_pages, whose 1310s worst-case ceiling a hung 1-page photo
    # used to hold a pool worker for.
    #
    # **The per-page allowance was raised 25 -> 45 on 2026-09-29, against
    # measurement rather than feel.** The old 25 came from a quiet-machine
    # rate of 15.6s/page plus ~60% headroom, and it was wrong about the
    # thing that matters: under a 10-way concurrent burst on two pool
    # workers, `large_35page_scanned.pdf` took **1020.9s for 35 pages =
    # 29.2s/page**, which already exceeds the 25s allowance before any
    # headroom at all. It was killed at its 935s budget for being slow,
    # not for hanging.
    #
    #     observed under load   29.2 s/page
    #     x1.5                  43.8 s/page  -> 45, rounded
    #     35-page budget        60 + 35*45 = 1635s   (was 935s)
    #     1-page budget         60 +  1*45 = 105s    (was 85s)
    #
    # This budget exists to catch a HANG -- a wedged native call that will
    # never return -- not to cap a document that is merely slow. A
    # too-tight budget converts slow documents into failures and, now that
    # TIMEOUT carries a retry, into repeated failures that cost a pool
    # worker each. The hard-kill grace below is what bounds an actual
    # wedge, and it is unchanged.
    ocr_timeout_base_s: float = 60
    ocr_timeout_per_page_s: float = 45
    # Grace between the SOFT deadline (checked between pages, raises
    # OcrTimeoutError and lets the worker record TIMEOUT and carry on) and
    # the HARD one (a watchdog thread that records TIMEOUT itself and then
    # os._exit()s the pool worker, because a single wedged native
    # PaddleOCR/Docling call can't be interrupted from Python). Sized to
    # cover one page's worth of work plus the failure-commit transaction.
    ocr_timeout_hard_grace_s: float = 30
    # How far the PARENT's pebble ceiling sits above the largest deadline
    # the in-worker watchdog can arm. This is the only bound that does not
    # depend on anything inside the worker process still working: the
    # watchdog is a Python thread, and a native call that holds the GIL
    # starves it -- observed live, where a 35-page scan ran past its 1665s
    # in-worker hard deadline and was only stopped by this ceiling at
    # 2370s. pebble force-terminates the worker, and the parent records
    # TIMEOUT from the outside (see consumer.py's ProcessExpired branch,
    # which tells that SIGKILL apart from an OOM kill by elapsed time).
    ocr_pool_ceiling_grace_s: float = 30

    # Real measured rate (15.6s/page) makes the production-prompt spec's own
    # OCR_MAX_PAGES=200 default ~52 minutes of one worker process monopolized
    # by a single document -- with only ocr_worker_processes=2 by default,
    # that's half the pool gone for the better part of an hour on one file.
    # 50 pages is ~13 minutes worst-case and comfortably covers realistic
    # single-document uploads; still operator-adjustable per deployment.
    ocr_max_pages: int = 50
    ocr_max_file_size_bytes: int = 100 * 1024 * 1024

    # -- Job leasing / reaper (see V36__ocr_job_leasing.sql) --
    # **Must stay larger than the longest a document can legitimately hold
    # a pool worker**, which is
    # `ocr_timeout_base_s + ocr_max_pages * ocr_timeout_per_page_s
    #  + 2 * ocr_timeout_hard_grace_s` -- 60 + 50*45 + 60 = **2370s** at
    # the current defaults. `OcrProcessPool.warn_on_misconfigured_lease`
    # checks exactly this at startup.
    #
    # Raised 1800 -> 2700 on 2026-09-29, as a direct consequence of raising
    # ocr_timeout_per_page_s to 45: the old 1800s lease is BELOW the new
    # 2370s worst case, and a lease that expires mid-inference does not
    # merely log -- the reaper reclaims the row from a worker that is still
    # working on it, requeues, and the retry restarts the same document
    # from scratch. A document that was never failing can livelock that way
    # until it is dead-lettered. Observed for real during Phase 1 close-out
    # against a deliberately shortened lease.
    ocr_lease_duration_s: int = 2700
    ocr_reaper_interval_s: int = 30
    # Linear backoff: a job's Nth requeue waits N * this many seconds
    # before it becomes claimable again (enforced by claim_job's
    # next_attempt_at check) -- simple and deterministic given
    # max_delivery_attempts is already small (=5), not worth a more
    # elaborate exponential formula.
    ocr_reaper_backoff_base_s: int = 30

    # MinIO settings were removed here. This service owns no object storage:
    # dms-core encrypts every object at rest, so reading a bucket directly
    # returned ciphertext. Documents are fetched from, and OCR artifacts
    # written back to, dms-core over the internal endpoint - see
    # dms_core_base_url below and content_client.py.

    # -- OCR behavior (OS-NFR-001/002/003) --
    min_confidence_threshold: float = 0.95
    ocr_languages: str = "en"
    ocr_render_dpi: int = 200

    # The real ceiling on a rendered page, replacing the fixed 1600px
    # longest-side cap (Phase 2 Step 4). Measured, not chosen: RSS grows
    # ~0.7 GiB per megapixel of input and PaddleOCR is OOM-killed above
    # ~6 MP per inference call at the container's 8.5 GiB limit with ONE
    # process -- production runs two. See
    # docs/ocr/phase2-verification.md Step 4.
    #
    # Expressed as area because area is what that constraint is
    # denominated in. A longest-side cap punished any page that was not
    # roughly A4-shaped: a 2000x500pt landscape page was held to
    # 1600x400 = 0.64 MP, a quarter of what an A4 page was allowed, for
    # no reason but its aspect ratio.
    #
    # 1.81 is the exact area the old 1600px longest-side cap produced on
    # A4 (1131x1600 = 1,809,600 px), chosen so this change alters the
    # SHAPE of the cap without altering what an ordinary page renders at.
    # A4 and Letter come out pixel-identical to before; only pages the
    # longest-side rule mis-served -- wide, tall, or very large -- move.
    #
    # It is deliberately NOT set to the ~6 MP the memory ceiling would
    # allow. Step 4 measured what the extra pixels buy on this corpus:
    # nothing. 137 and 200 DPI produced byte-identical text, and the 6pt
    # fine print reads with zero character errors at both, for +60% time
    # and +70% memory. Raising this is a cost with no measured benefit
    # until there is a real corpus to re-measure on (see
    # docs/ocr/phase2-verification.md Step 4, "DECISION").
    ocr_render_max_mp: float = 1.81

    # Preview render for the review screen (Phase 4c). Higher than
    # `ocr_render_dpi` because these pixels are for a human eye at a
    # browser zoom, not for an OCR engine -- 200 DPI text that reads
    # perfectly to PaddleOCR is soft on screen when the reviewer is
    # checking whether an 8pt figure is a 3 or an 8, which is exactly
    # what the queue sends them here to do. `ocr_render_max_mp` still
    # caps the result, so a large page comes back smaller than this
    # implies -- which is why PageRender reports its real pixel size.
    #
    # Does NOT affect extraction: the preview path never feeds an engine.
    ocr_preview_dpi: int = 300

    # A reviewer is sitting in front of the browser waiting. A preview
    # render or a one-box OCR that has not answered in 90s is not going
    # to; failing fast frees the pool worker for the document queue
    # rather than holding it for the whole per-document budget.
    ocr_preview_timeout_s: float = 90.0

    # -- Per-page routing (production prompt section 3.6, Phase 2 Step 7).
    # Replaces "any native text on the page -> native_text", a rule that
    # cost two entire eval conditions, both at 0.0% field accuracy:
    # `garbage_text_layer` (an invisible (cid:NN) layer over a real scan)
    # and `hybrid_page` (a typed header over a scanned body).
    ocr_routing_enabled: bool = True

    # -- Table-structure safety net (Phase 2 Step 7 close-out) --
    # TableFormer's cost follows the grid it predicts, and that prediction
    # is unbounded. Measured on a one-page PDF with /Rotate 90: a 378x2
    # grid on a page holding 24 cells, 87s, and all 24 cells then dropped
    # for matching no band -- 87 seconds that produced nothing. Upright,
    # the same page is 1.2s.
    #
    # 20s is chosen against measurement, not taste: the most expensive
    # LEGITIMATE table in the corpus (a real 5x4 ruled invoice table)
    # costs ~4s of table pass, and the largest real document in the
    # fixture set stays under 10s. 20s leaves a 2x margin over anything
    # observed to be real work while cutting the pathological case by
    # roughly 4x. 0 disables the bound.
    ocr_table_timeout_per_page_s: float = 20.0

    # Bake /Rotate into the page content before Docling sees it. PyMuPDF
    # already applies /Rotate for our own text extraction and rendering,
    # so this changes nothing we read -- verified as a 0.000pt bbox delta
    # across all 24 words of the rotated fixture -- but it takes Docling
    # from 138.9s to 2.3s on that page. Kept as a switch because it
    # rewrites the PDF, and a rewrite is the kind of thing worth being
    # able to turn off when diagnosing something else.
    ocr_normalize_page_rotation: bool = True

    # -- Per-page image quality (production prompt 3.3, Phase 2 Step 5) --
    # Measured on every rasterized page and reported in `pages[].quality`;
    # below a threshold the page also gets `flags.poor_image_quality` with
    # the reasons. A page is NEVER rejected for quality -- 3.3 is explicit,
    # and the flag exists to drive the "please re-scan at a higher
    # resolution" message and Phase 3's requires_review rule.
    ocr_quality_enabled: bool = True
    # 150 DPI is the floor below which small print stops surviving
    # recognition. The corpus's own `low_dpi_scan_72` condition sits at 72
    # and scores 92.2% on large text while losing `cost_centre` (6pt)
    # three times out of three -- which is exactly the shape of failure a
    # user should be told to re-scan for.
    ocr_quality_min_dpi: float = 150.0
    # Variance-of-Laplacian is resolution- and content-dependent, so this
    # is a starting value, NOT a calibrated one: it has never been
    # measured against a genuinely blurred real photograph, only against
    # the corpus's synthetic degradation. Listed with the other
    # provisional thresholds in docs/ocr/phase2-verification.md.
    ocr_quality_min_blur: float = 100.0
    # **Disabled (0), and that is a finding, not a tuning choice.**
    #
    # This was 35.0, set from a guess that "a clean bilevel scan runs
    # 70-110". Measured against the corpus, that is simply not true of
    # document pages, and the metric is inverted for the job:
    #
    #     clean_scan_300          21.9      <- clean
    #     native_pdf              26.7      <- clean
    #     phone_photo_perspective 20.3      <- degraded
    #     low_dpi_scan_72         19.9      <- degraded
    #     shadowed_photo          46.5      <- degraded, and the HIGHEST
    #
    # RMS contrast is the standard deviation of intensity. A document page
    # is mostly white with sparse black text, so its std-dev is naturally
    # LOW; a shadow is a large dark region, which RAISES it. The one
    # condition this check existed to catch scores highest of all, and the
    # clean and degraded ranges overlap -- no threshold separates them, in
    # either direction. At 35.0 it flagged every clean page in the corpus.
    #
    # The value is still measured and reported in `pages[].quality`, so
    # nothing is lost; it just may not raise `poor_image_quality`. A
    # working check needs a different metric -- local/adaptive contrast,
    # or foreground/background separation via Otsu, which measures the ink
    # against the paper rather than the whole page against itself. Not
    # built; set a positive value to re-enable the old behaviour.
    ocr_quality_min_contrast: float = 0.0

    # -- Locale for parsing amounts and dates (production prompt 3.8) --
    # `parse_amount`/`parse_date` already take a locale per call; what did
    # not exist was anything to supply one, so every call used the
    # `day_first` default. This is the process-wide setting, NOT the
    # per-tenant wiring 3.8 ultimately wants -- that needs dms-core to
    # send it on `document.version.ocr.requested`, and is still pending.
    # A comma-decimal tenant's `1.234` stays flagged ambiguous until then.
    ocr_locale: str = "day_first"

    # The currency to assume when the page carries NO signal at all -- no
    # ISO code, no symbol, no `Rs`, no `/=`. Six of the real corpus's
    # receipts are like this: a Sri Lankan hospital prints "Total 3,000.00"
    # and every human reading it knows the unit, because there is only one
    # currency in the building.
    #
    # Applied with `status="defaulted"`, which is deliberately not
    # `extracted`: it is a real value a consumer may use, it is NOT
    # something OCR read, so it is excluded from OCR accuracy and is never
    # a reason to send a document to review. A tenant that genuinely bills
    # in several currencies should set this empty and take `not_found`,
    # which is the honest answer there.
    #
    # Process-wide for the same reason `ocr_locale` is: per-tenant needs
    # dms-core to send it on `document.version.ocr.requested`.
    ocr_default_currency: str = "LKR"

    # -- Searchable PDF rendition (production prompt 3.11, Phase 4a) --
    # The page raster plus an invisible OCR text layer, written to the OCR
    # output bucket and recorded in `renditions`. Off makes the whole step
    # a no-op: a rendition is additive, and a deployment that does not
    # want the extra object per document should be able to say so.
    ocr_searchable_pdf_enabled: bool = True
    # Above this fraction of unreadable glyphs, the text layer is junk and
    # the page is OCR'd instead. 0.30 is deliberately permissive: a real
    # text layer with a few unmapped glyphs (a logo font, a ligature) must
    # NOT be thrown away, because re-OCRing a good page is a real accuracy
    # loss. The failure this catches is not subtle -- a scanner junk layer
    # measures far above this, not just over it.
    ocr_routing_max_garbage_ratio: float = 0.30
    # Below this fraction of word-shaped tokens, likewise. Same asymmetry,
    # so it sits low.
    ocr_routing_min_plausibility: float = 0.50
    # A page with a good text layer AND this much of its area as image
    # that the text layer does not explain is a hybrid page: the text is
    # kept and the image regions are OCR'd and merged. 0.15 of an A4 page
    # is roughly a quarter of its height -- large enough that a logo, a
    # signature block or a letterhead graphic does not trigger a second
    # inference pass on every native PDF in the system.
    ocr_routing_min_uncovered_image: float = 0.15

    # -- Retry policy, per failure cause --
    #
    # `max_delivery_attempts` alone was blind to WHY a job died. The
    # reaper retried anything left 'processing' with an expired lease up
    # to 5 times, on the assumption that a death is transient -- and for
    # a document that is simply too big or too slow, it never is. Measured
    # cost of that assumption: the 50-upload run at 4 MP spent roughly 40
    # of its 60 minutes retrying one 35-page scan that could not finish,
    # 5 times, each attempt burning the full 935s budget.
    #
    # Format: `CODE=attempts,CODE=attempts`. Attempts is the number of
    # RETRIES after the first try, so 0 means "try once, never again".
    # Parsed and validated at startup (`retry_attempts_by_code`) -- an
    # unknown code or a non-integer fails the process rather than being
    # silently ignored, because a typo here would quietly restore the
    # behaviour this exists to remove.
    ocr_retry_attempts_by_code: str = (
        "TIMEOUT=1,"
        "ENCRYPTED_PDF=0,CORRUPT_FILE=0,UNSUPPORTED_FORMAT=0,"
        "TOO_MANY_PAGES=0,FILE_TOO_LARGE=0,"
        "WORKER_CRASH=3,ENGINE_ERROR=3"
    )
    # A version OOM-killed twice is not unlucky; it is too big for this
    # worker's memory at this configuration, and a third identical attempt
    # only costs another pool worker. Dead-lettered immediately at this
    # count with a message that says so.
    ocr_max_worker_crashes_per_version: int = 2

    @property
    def retry_attempts_by_code(self) -> dict[str, int]:
        """`ocr_retry_attempts_by_code` parsed, validated, and frozen.

        Raises at startup on anything malformed. That is deliberate: the
        alternative -- skipping a bad entry with a warning -- means a
        deployment with a typo silently falls back to retrying everything
        5 times, which is precisely the behaviour this setting exists to
        stop, and nobody would notice until a queue backed up.
        """
        from .errors import KNOWN_ERROR_CODES

        parsed: dict[str, int] = {}
        raw = (self.ocr_retry_attempts_by_code or "").strip()
        if not raw:
            return parsed
        for entry in raw.split(","):
            entry = entry.strip()
            if not entry:
                continue
            if "=" not in entry:
                raise ValueError(
                    f"OCR_RETRY_ATTEMPTS_BY_CODE: {entry!r} is not CODE=attempts"
                )
            code, _, attempts = entry.partition("=")
            code = code.strip().upper()
            if code not in KNOWN_ERROR_CODES:
                raise ValueError(
                    f"OCR_RETRY_ATTEMPTS_BY_CODE: unknown error code {code!r}; "
                    f"known codes are {', '.join(sorted(KNOWN_ERROR_CODES))}"
                )
            try:
                count = int(attempts.strip())
            except ValueError:
                raise ValueError(
                    f"OCR_RETRY_ATTEMPTS_BY_CODE: {code}={attempts.strip()!r} is not an integer"
                ) from None
            if count < 0:
                raise ValueError(f"OCR_RETRY_ATTEMPTS_BY_CODE: {code} cannot be negative")
            parsed[code] = count
        return parsed

    def retries_allowed_for(self, code: str | None) -> int | None:
        """How many RETRIES (attempts after the first) `code` may have, or
        None when this code has no specific policy.

        Two units meet here and conflating them is an off-by-one that
        silently doubles a retry budget, so they are kept apart
        deliberately: `ocr_retry_attempts_by_code` counts **retries**
        (0 = try once, never again), which is the number an operator
        actually thinks in, while `max_delivery_attempts` counts **total
        attempts**. `max_attempts_for` is the one the reaper compares
        against, and it does the conversion in exactly one place.
        """
        if not code:
            return None
        return self.retry_attempts_by_code.get(code.upper())

    def max_attempts_for(self, code: str | None) -> int:
        """Total attempts allowed for a job that died with `code`.

        **An unrecognized or missing cause gets `WORKER_CRASH`'s budget**,
        not a separate one, because "no cause was recorded" is very often
        a crash one level up: a container-level OOM kills the *parent*
        too, so nothing survives to write `WORKER_CRASH:` and the job
        lands here instead. The reaper already dead-letters such a job
        labelled `WORKER_CRASH`, so the budget has to match the label --
        otherwise the same failure is retried a different number of times
        depending only on whether the process that would have described it
        also died.

        Falls back to `max_delivery_attempts` only when `WORKER_CRASH`
        itself has no configured policy.
        """
        retries = self.retries_allowed_for(code)
        if retries is not None:
            return retries + 1
        from .errors import WorkerCrashError

        crash_retries = self.retry_attempts_by_code.get(WorkerCrashError.code)
        return self.max_delivery_attempts if crash_retries is None else crash_retries + 1

    # -- Preprocessing (production prompt section 3.4: "each step
    # individually toggleable in config and recorded in the output").
    # Step 3 of Phase 2 is the first of these: whole-page orientation.
    #
    # On by default because the baseline measured the cost of NOT doing
    # it: the eval corpus's three rotated conditions scored 11.7-14.7%
    # field accuracy against 95.3% for the same documents upright
    # (eval/reports/baseline-2026-09-25.json), i.e. a sideways page is
    # very nearly a total loss today. The classifier itself measured 90/90
    # correct on that corpus with no false positive on any of the 63
    # upright pages, ~0.03s and 1 MiB per page -- see
    # docs/ocr/phase2-verification.md Step 3.
    ocr_orientation_enabled: bool = True
    # A prediction below this is recorded in `pages[].preprocessing` and
    # NOT applied. The asymmetry is the point: rotating an upright page
    # costs ~83 accuracy points, so "unsure" must mean "leave it alone".
    #
    # 0.6 is a floor, not a calibrated threshold, and the README says so:
    # PP-LCNet_x1_0_doc_ori returned ~0.92 for every page in the corpus
    # regardless of condition or of whether it was right, so its score
    # carries little discriminative signal to calibrate against. It is
    # here to catch a degenerate page (blank, near-black, a scanner
    # separator sheet) whose score does collapse, not to trade precision
    # against recall on ordinary documents.
    ocr_orientation_min_confidence: float = 0.6

    # Selectable-field extraction (My Documents upload page): a much lower
    # bar than min_confidence_threshold above -- that one gates the whole
    # document's `flags.low_confidence`/`requires_review`, this one only
    # decides whether one individual requested field's own value is
    # trustworthy enough to mark "extracted" vs. "low_confidence" (the
    # value is returned either way, never dropped -- see extraction.py).
    field_low_confidence_threshold: float = 0.6

    # -- API --
    api_host: str = "0.0.0.0"
    api_port: int = 8090

    # -- Internal-only access (see README "Auth & service exposure") --
    # ocr-worker has no login of its own and is never reachable from
    # outside the docker network (no published host port). This token is
    # defense in depth on top of that network boundary, not an
    # authentication scheme -- dms-core is the only intended caller.
    internal_service_token: str = "changeme-local-dev-token"

    # Where dms-core answers, for reading document bytes. This worker no
    # longer reads object storage itself: dms-core encrypts every object at
    # rest, so a direct read would return ciphertext. See content_client.py.
    dms_core_base_url: str = "http://dms-core:8080"
    dms_core_connect_timeout_s: float = 5.0
    # Generous: this is a document download, and a large scan over a busy
    # network legitimately takes a while. The job's own deadline is the real
    # bound on how long processing may take.
    dms_core_read_timeout_s: float = 120.0

    # Where downloaded plaintext is written while a job runs.
    #
    # MUST be a tmpfs mount in any real deployment. Decrypted document content
    # on a container's writable layer survives the job, lands in image layers
    # and snapshots, and is exactly the thing encryption at rest exists to
    # prevent. content_client.py deletes each file when its job finishes; the
    # tmpfs is what makes a crash between those two points harmless.
    ocr_tmp_dir: str = "/dev/shm/ocr"

    @property
    def effective_prefetch_count(self) -> int:
        """Unset `prefetch_count` means "exactly as many as this worker can
        actually start", i.e. `ocr_worker_processes`.

        Before Phase 1 close-out this defaulted to a flat 4 while
        `ocr_worker_processes` defaulted to 2, so RabbitMQ handed this
        consumer two deliveries it had no pool worker free to run -- they
        sat unacked, invisible to any other consumer, for however long the
        two in-flight documents took (up to ~1000s for a 35-page scan in
        the Phase 1 run). Prefetch above the pool size buys no throughput
        here (the pool, not the queue, is the bottleneck) and only delays
        redistribution, so they are tied together.

        NOTE: each consumer sets QoS on its OWN channel, so with both
        ScanCompletedConsumer and OcrRequestedConsumer connected the
        process-wide ceiling is 2 x this. That is deliberate and currently
        moot -- nothing publishes `document.version.scan.completed` until
        scan-worker exists (see README "Known upstream gap") -- but it is
        the thing to revisit if it ever does.
        """
        return self.prefetch_count if self.prefetch_count is not None else self.ocr_worker_processes


def get_settings() -> Settings:
    return Settings()
