"""Phase 2 evaluation harness (docs/ocr/ocr-production-prompt.md section 5).

This package is the measuring instrument for every accuracy change in
Phase 2: it builds a ground-truth corpus, runs the real `OcrPipeline`
over it, and reports CER/WER, per-field accuracy, latency percentiles and
peak RSS. Nothing in `src/ocr_worker` imports it -- it is a tool, not a
runtime dependency, which is why it lives beside `src/` and `tests/`
rather than inside the package.

It is deliberately runnable without a rebuild: `run.py` drives the
pipeline in-process with `src/` importable from the source tree, so a
config toggle can be evaluated by re-running one command inside the
already-running container.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Same bootstrap `tests/conftest.py` uses -- the worker package lives in
# `src/`, which is on PYTHONPATH inside the image but not when this is run
# straight from a checkout.
_SRC = Path(__file__).resolve().parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))
