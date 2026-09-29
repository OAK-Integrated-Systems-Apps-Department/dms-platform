"""Computed per-field confidence (production prompt section 3.10).

    field_confidence = mean OCR confidence of the source tokens
                       x tier weight
                       x validation factor

replacing the hardcoded 0.5/0.6/0.75/0.8 literals that were scattered
through `extraction.py`'s per-field branches. Those numbers encoded a
real intuition -- a labelled match beats a positional guess -- but they
were unfalsifiable, could not move when OCR was uncertain about the
actual characters, and could not be calibrated.

**Native text counts as 1.0**, per 3.10. A PDF's own text layer is not a
recognition result: there is no character-level uncertainty to propagate,
so the OCR term drops out and the tier weight and validation factor carry
the whole score.

**The weights and the threshold here are PROVISIONAL.** 3.10 requires
them calibrated against the evaluation corpus so that fields marked
`extracted` are correct >=95% of the time, and that calibration needs the
real labelled set, which is drafted but not yet reviewed (see
docs/ocr/phase2-verification.md). They are ordered sensibly and spaced
widely; they are not tuned. Everything here is deliberately config-driven
so calibration is a settings change, not a code change.
"""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)

# Extraction tiers, strongest first (production prompt 3.8's ordering).
TIER_ZONE = "zone"            # a configured zone template matched
TIER_ANCHOR = "anchor"        # anchor-relative match (Phase 3, behind a flag)
TIER_SPATIAL = "spatial"      # label found, value taken from its line/below it
TIER_PATTERN = "pattern"      # a regex/pattern tier matched, no label
TIER_FALLBACK = "fallback"    # positional or derived guess (letterhead, summary)

# PROVISIONAL. Ordering is the claim; the exact values are not.
DEFAULT_TIER_WEIGHTS: dict[str, float] = {
    TIER_ZONE: 1.00,
    TIER_ANCHOR: 0.95,
    TIER_SPATIAL: 0.90,
    TIER_PATTERN: 0.85,
    TIER_FALLBACK: 0.60,
}

# Validation factors. A field with no applicable rule is NOT penalised --
# "nothing to check" and "checked and passed" both multiply by 1.0, because
# penalising the first would quietly mark every unvalidatable field lower
# than a validated one for no evidential reason.
VALIDATION_PASS = 1.0
VALIDATION_NONE = 1.0
# A failure does not zero the score. The value is still a real reading of
# the document and is kept -- it is the STATUS that must stop saying
# "extracted" (see `requires_review`). Zeroing would make a failed
# cross-check indistinguishable from "found nothing", which are very
# different things to a reviewer.
VALIDATION_FAIL = 0.5
# An ambiguous parse (a date that could be day-first or month-first, an
# amount whose decimal separator is undecidable) is worse than a clean
# pass and better than a contradiction.
VALIDATION_AMBIGUOUS = 0.75


def compute(
    *,
    ocr_confidence: float | None,
    tier: str,
    validation_factor: float = VALIDATION_NONE,
    tier_weights: dict[str, float] | None = None,
) -> float:
    """The 3.10 product, clamped to [0, 1].

    `ocr_confidence=None` means native text (or any source with no
    character-level uncertainty) and contributes 1.0. That is not the same
    as 0.0, and conflating them would make every native-text field
    worthless.

    An unknown tier falls back to `TIER_FALLBACK`'s weight rather than
    1.0: a tier nobody has weighted is a tier nobody has evaluated, and
    the safe direction for an unevaluated source is *less* confident.
    """
    weights = tier_weights or DEFAULT_TIER_WEIGHTS
    weight = weights.get(tier)
    if weight is None:
        logger.debug("No tier weight configured for %r; using the fallback weight", tier)
        weight = weights.get(TIER_FALLBACK, DEFAULT_TIER_WEIGHTS[TIER_FALLBACK])

    ocr_term = 1.0 if ocr_confidence is None else float(ocr_confidence)
    score = ocr_term * float(weight) * float(validation_factor)
    return min(max(score, 0.0), 1.0)


def mean_ocr_confidence_for_bbox(
    line_confidences: list[tuple[tuple[int, int, int, int], float]] | None,
    bbox: tuple[int, int, int, int] | None,
    *,
    min_overlap: float = 0.3,
) -> float | None:
    """Mean recognition confidence of the OCR lines a value came from.

    Returns None when the page carries no per-line confidence at all --
    which is exactly the native-text case, where `compute` then treats the
    OCR term as 1.0. Also None when nothing overlaps, because inventing a
    number for "we could not find the source tokens" would be worse than
    saying so.

    Overlap rather than equality: a field's bbox is frequently the VALUE's
    box, clipped out of a line that also held its label, so it is a strict
    sub-box of the line that produced it and would never compare equal.
    """
    if not line_confidences or bbox is None:
        return None

    x0, y0, x1, y1 = bbox
    area = max(0, x1 - x0) * max(0, y1 - y0)
    if area <= 0:
        return None

    hits: list[float] = []
    for line_bbox, confidence in line_confidences:
        lx0, ly0, lx1, ly1 = line_bbox
        ox0, oy0 = max(x0, lx0), max(y0, ly0)
        ox1, oy1 = min(x1, lx1), min(y1, ly1)
        overlap = max(0, ox1 - ox0) * max(0, oy1 - oy0)
        if overlap / area >= min_overlap:
            hits.append(float(confidence))

    if not hits:
        return None
    return sum(hits) / len(hits)
