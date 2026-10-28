"""Download every model weight this worker needs, at IMAGE BUILD TIME.

Production prompt section 3.1: "Pre-download all model weights at image
build time and warm them up at process start". The second half was built;
this is the first half, and without it a cold production start needs
outbound access to the PaddleX model server and the HuggingFace Hub to
fetch ~650 MB before it can read its first document.

**It calls the same `warm_up()` methods the worker calls at process
start**, rather than fetching a hand-written list of model names. That is
the whole design: a curated list is a second source of truth that drifts
the moment someone adds a model, and it drifts silently -- the image
still builds, and the failure only appears on a restricted-egress
deployment much later. Driving the real warm-up path means the baked set
is by construction the set the runtime asks for.

`LayoutEngine.warm_up()` in particular runs a real Docling conversion,
not just a constructor, because Docling's layout and table-structure
sub-models are lazily initialised inside `.convert()` (see its docstring).
A constructor-only prefetch would miss them.

Verification is empirical, not by inspection: the image is run with
`--network none` and no volumes against a real document. If anything is
still fetched at runtime, that run fails. See the README.
"""

from __future__ import annotations

import sys
import time


def main() -> int:
    # Imported inside main so an import failure is reported with the same
    # framing as a download failure, rather than as a bare traceback at
    # module scope during `docker build`.
    from ocr_worker.config import Settings
    from ocr_worker.layout import LayoutEngine
    from ocr_worker.ocr_engine import PaddleOcrEngine
    from ocr_worker.orientation import PaddleDocOrientationClassifier

    settings = Settings()

    # Every language the image should be able to serve offline. Defaults
    # to the configured one; `--build-arg OCR_PREFETCH_LANGS="en,ta"`
    # bakes more. A language NOT baked here still works -- it downloads
    # its weights on first use -- but only on a deployment with egress,
    # which is exactly the assumption this script exists to remove.
    languages = [lang.strip() for lang in settings.ocr_languages.split(",") if lang.strip()]

    steps = [
        (
            "Docling layout + table structure (via a real conversion)",
            lambda: LayoutEngine(
                table_timeout_per_page_s=settings.ocr_table_timeout_per_page_s,
                normalize_rotation=settings.ocr_normalize_page_rotation,
            ).warm_up(),
        ),
        (
            "PaddleOCR orientation classifier (PP-LCNet_x1_0_doc_ori)",
            lambda: PaddleDocOrientationClassifier().warm_up(),
        ),
    ]
    for lang in languages:
        steps.append((
            f"PaddleOCR detection + recognition, lang={lang!r}",
            # Default argument binds the loop variable at definition time.
            lambda lang=lang: PaddleOcrEngine().warm_up(lang=lang),
        ))

    for name, run in steps:
        print(f"[prefetch] {name} ...", flush=True)
        started = time.monotonic()
        run()
        print(f"[prefetch] {name} -- done in {time.monotonic() - started:.1f}s", flush=True)

    print(f"[prefetch] all weights cached for languages: {', '.join(languages)}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
