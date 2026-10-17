"""`eval.run`'s staleness guard.

Delegates to `scripts/dev/_staleness_guard.py` rather than reimplementing
it. Two copies of "is this container stale" would drift, and the one that
drifted would be the one that silently stopped refusing -- which is the
failure mode this whole guard exists to remove.

Loaded by path because `scripts/dev` is not an importable package and the
eval package must not grow a dependency on the repo layout beyond this one
line.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

_GUARD = Path(__file__).resolve().parents[4] / "scripts/dev/_staleness_guard.py"


def enforce(*, what: str) -> None:
    if not _GUARD.exists():
        print(f"[{what}] staleness guard not found at {_GUARD}; continuing", file=sys.stderr)
        return
    spec = importlib.util.spec_from_file_location("_staleness_guard", _GUARD)
    if spec is None or spec.loader is None:
        print(f"[{what}] staleness guard could not be loaded; continuing", file=sys.stderr)
        return
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.enforce(what)
