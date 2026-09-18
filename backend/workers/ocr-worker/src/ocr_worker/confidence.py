from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ConfidenceDecision:
    mean_confidence: float
    threshold: float
    flagged: bool

    @property
    def accepted(self) -> bool:
        return not self.flagged


class ConfidenceEvaluator:
    """OS-NFR-001: configurable minimum confidence threshold (default 95%).

    Below-threshold results are never silently accepted -- they are still
    persisted (so the extracted text isn't thrown away) but flagged, per the
    alternate flow "ambiguous rubber-band selection -> low-confidence flag
    for review". See docs/events/ocr-scanning-events.md for how `flagged`
    surfaces on the DB row / event, since neither `ocr_outputs.status` nor
    `document_versions.ocr_status` has a distinct enum value for it.
    """

    def __init__(self, threshold: float = 0.95):
        if not 0.0 <= threshold <= 1.0:
            raise ValueError(f"threshold must be within [0.0, 1.0], got {threshold}")
        self.threshold = threshold

    def evaluate(self, mean_confidence: float) -> ConfidenceDecision:
        return ConfidenceDecision(
            mean_confidence=mean_confidence,
            threshold=self.threshold,
            flagged=mean_confidence < self.threshold,
        )
