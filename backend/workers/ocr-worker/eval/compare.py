"""`python -m eval.compare BASELINE.json CANDIDATE.json` -- the gate.

Implements the decision rule Phase 2 works under:

- **The real set decides.** When it has data, whether a change is kept
  rests on it.
- **The synthetic set can only veto.** A synthetic regression fails the
  gate regardless of what the real set says, because it means something
  structural broke. A synthetic *improvement* justifies nothing on its
  own -- the set is generated and labelled by the same process being
  measured, so it can be improved by changes that do nothing for a real
  document.
- **With no real data the verdict is VETO-ONLY.** Exit 0, because
  nothing is broken, but the output says in as many words that the run
  cannot justify keeping the change.

The two sets are compared separately and never averaged.

A tolerance exists because the pipeline is not bit-deterministic:
PaddleOCR on CPU can differ in the last decimal between runs, so a bare
`<` comparison would fail on noise and train everyone to ignore the gate.
The default is deliberately tight -- large enough to absorb jitter, far
too small to hide a real regression.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

DEFAULT_TOLERANCE = 0.005
# Memory is noisier than accuracy (allocator and page-cache behaviour vary
# run to run), so the same tolerance would produce false alarms.
MEMORY_TOLERANCE = 1.05

SYNTHETIC = "synthetic"
REAL = "real"

# (key, higher_is_better)
HEADLINE_GATES = [
    ("field_accuracy", True),
    ("extracted_precision", True),
    ("cer_mean", False),
    ("wer_mean", False),
]


def _load(path: str) -> dict:
    report = json.loads(Path(path).read_text(encoding="utf-8"))
    if "sets" not in report:
        # A pre-two-set report. It was, by definition, synthetic only --
        # the real set did not exist when it was written. Adapting it here
        # rather than rejecting it means an early baseline stays usable as
        # a comparison point instead of costing a fresh 30-minute run.
        report = {
            "variant": report.get("variant", "unknown"),
            "settings": report.get("settings", {}),
            "memory": report.get("memory", {}),
            "sets": {SYNTHETIC: {
                "set": SYNTHETIC,
                "documents": (report.get("corpus") or {}).get("documents", 0),
                "headline": report.get("headline", {}),
                "by_condition": report.get("by_condition", {}),
                "fields": report.get("fields", {}),
            }},
        }
    return report


def _delta_line(key: str, base, cand, higher_is_better: bool) -> str:
    if base is None or cand is None:
        return f"{key}: {base} -> {cand} (not comparable)"
    delta = cand - base
    good = (delta >= 0) if higher_is_better else (delta <= 0)
    return f"{key}: {base:.4f} -> {cand:.4f} ({delta:+.4f} {'ok' if good else 'WORSE'})"


def _compare_set(set_name: str, baseline: dict, candidate: dict, tolerance: float):
    """Returns (regressions, improvements, notes) for one set."""
    regressions: list[str] = []
    improvements: list[str] = []
    notes: list[str] = []

    base_set = (baseline.get("sets") or {}).get(set_name)
    cand_set = (candidate.get("sets") or {}).get(set_name)
    if not base_set or not cand_set:
        notes.append(f"{set_name}: not present in both reports")
        return regressions, improvements, notes
    if not base_set.get("documents") or not cand_set.get("documents"):
        notes.append(f"{set_name}: no documents")
        return regressions, improvements, notes

    if base_set["documents"] != cand_set["documents"]:
        notes.append(
            f"{set_name}: document count changed, {base_set['documents']} -> "
            f"{cand_set['documents']} -- the sets are not the same, read with care"
        )

    for key, higher_is_better in HEADLINE_GATES:
        base_value = (base_set.get("headline") or {}).get(key)
        cand_value = (cand_set.get("headline") or {}).get(key)
        line = f"{set_name}: " + _delta_line(key, base_value, cand_value, higher_is_better)
        if base_value is None or cand_value is None:
            notes.append(line)
            continue
        delta = cand_value - base_value
        if (delta < -tolerance) if higher_is_better else (delta > tolerance):
            regressions.append(line)
        elif (delta > tolerance) if higher_is_better else (delta < -tolerance):
            improvements.append(line)
        else:
            notes.append(line)

    # Per-condition field accuracy: a change that lifts the average while
    # destroying one condition must not pass.
    shared = set(base_set.get("by_condition") or {}) & set(cand_set.get("by_condition") or {})
    for name in sorted(shared):
        base_value = base_set["by_condition"][name]["field_exact_match"]
        cand_value = cand_set["by_condition"][name]["field_exact_match"]
        delta = cand_value - base_value
        if delta < -tolerance:
            regressions.append(
                f"{set_name}: condition `{name}` field_exact_match "
                f"{base_value:.4f} -> {cand_value:.4f} ({delta:+.4f} WORSE)"
            )

    return regressions, improvements, notes


def compare(baseline: dict, candidate: dict, tolerance: float) -> dict:
    result = {
        "synthetic_regressions": [],
        "real_regressions": [],
        "real_improvements": [],
        "synthetic_improvements": [],
        "notes": [],
        "memory_regression": None,
        "has_real_data": False,
    }

    if baseline.get("settings") != candidate.get("settings"):
        # Not a failure -- changing settings is the entire point of a
        # variant run. Surfaced loudly so a comparison is never read as
        # like-for-like when it is not.
        result["notes"].append(
            f"settings differ: baseline={json.dumps(baseline.get('settings'))} "
            f"candidate={json.dumps(candidate.get('settings'))}"
        )

    syn_reg, syn_imp, syn_notes = _compare_set(SYNTHETIC, baseline, candidate, tolerance)
    real_reg, real_imp, real_notes = _compare_set(REAL, baseline, candidate, tolerance)

    result["synthetic_regressions"] = syn_reg
    result["synthetic_improvements"] = syn_imp
    result["real_regressions"] = real_reg
    result["real_improvements"] = real_imp
    result["notes"] += syn_notes + real_notes

    cand_real = (candidate.get("sets") or {}).get(REAL) or {}
    base_real = (baseline.get("sets") or {}).get(REAL) or {}
    result["has_real_data"] = bool(cand_real.get("documents")) and bool(base_real.get("documents"))

    base_peak = (baseline.get("memory") or {}).get("peak_gib")
    cand_peak = (candidate.get("memory") or {}).get("peak_gib")
    if base_peak and cand_peak:
        if cand_peak > base_peak * MEMORY_TOLERANCE:
            result["memory_regression"] = (
                f"peak memory {base_peak} GiB -> {cand_peak} GiB "
                f"(+{cand_peak - base_peak:.3f} GiB)"
            )
        else:
            result["notes"].append(f"peak memory: {base_peak} GiB -> {cand_peak} GiB")

    return result


def verdict(result: dict) -> tuple[str, int, str]:
    """Returns (verdict, exit code, one-line explanation).

    The explanation must name the thing that actually blocked. An earlier
    version fell through to "the real set regressed" whenever anything
    did -- so a run whose ONLY problem was peak memory reported a
    regression in a set that, with no real corpus, does not even exist.
    A gate that misdiagnoses sends someone to look in the wrong place,
    which is worse than one that just says "failed".
    """
    if result["real_regressions"]:
        return "FAILED", 1, "the real set regressed"
    if result["synthetic_regressions"]:
        return "VETOED", 1, "the synthetic regression set broke; that vetoes the change"
    if result["memory_regression"]:
        return "FAILED", 1, f"peak memory regressed: {result['memory_regression']}"

    if result["has_real_data"]:
        if result["real_improvements"]:
            return "JUSTIFIED", 0, "the real set improved and nothing regressed"
        return "NEUTRAL", 0, (
            "nothing regressed, but the real set did not improve -- "
            "this does not justify keeping the change"
        )

    return "VETO-ONLY", 0, (
        "no real data: nothing regressed, but a synthetic-only result "
        "cannot justify keeping the change"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Gate a candidate eval report against a baseline.")
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--tolerance", type=float, default=DEFAULT_TOLERANCE)
    args = parser.parse_args(argv)

    baseline = _load(args.baseline)
    candidate = _load(args.candidate)
    result = compare(baseline, candidate, args.tolerance)
    name, code, why = verdict(result)

    def counts(report):
        sets = report.get("sets") or {}
        return ", ".join(
            f"{s}={(sets.get(s) or {}).get('documents', 0)}" for s in (REAL, SYNTHETIC)
        )

    print(f"baseline:  {args.baseline} ({baseline.get('variant')}; {counts(baseline)})")
    print(f"candidate: {args.candidate} ({candidate.get('variant')}; {counts(candidate)})")
    print("")
    for note in result["notes"]:
        print(f"  . {note}")
    for line in result["real_improvements"]:
        print(f"  + {line}")
    for line in result["synthetic_improvements"]:
        print(f"  + {line} (synthetic -- cannot justify on its own)")
    for line in result["synthetic_regressions"] + result["real_regressions"]:
        print(f"  X {line}")
    if result["memory_regression"]:
        print(f"  X {result['memory_regression']}")

    print("")
    print(f"{name}: {why}")
    return code


if __name__ == "__main__":
    sys.exit(main())
