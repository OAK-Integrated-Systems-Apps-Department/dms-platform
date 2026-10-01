"""`requires_review` (production prompt section 3.10).

    requires_review = any mandatory field not extracted
                      OR any validation failure
                      OR poor image quality

replacing `mean page confidence < 0.95`, which was the only input before.

**Why the old rule had to go.** A mean over pages is the wrong shape for
the question. It is dominated by the bulk of a page's body text, which is
usually easy, while the thing a reviewer actually needs to see is one
mandatory field that came back empty -- a 40-page contract whose
`contract_value` is missing averages out to a confident document. It also
could not see a *contradiction*: a total that disagrees with its own line
items is read with perfect confidence and is still wrong.

The three inputs are deliberately unweighted and OR'd. This is a routing
decision, not a score: any one of them is sufficient reason for a human to
look, and averaging them would let two of them cancel the third.

`low_confidence` is kept as a separate flag, and is no longer a synonym
for `requires_review`. `docs/events/ocr-scanning-events.md` recorded that
the two were always equal because confidence was the only criterion; they
now genuinely differ, and a consumer that wants "the model was unsure"
should read `low_confidence` while one that wants "a human must look"
reads `requires_review`.
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class ReviewDecision:
    requires_review: bool
    reasons: list[str] = field(default_factory=list)
    missing_mandatory: list[str] = field(default_factory=list)
    validation_failures: list[str] = field(default_factory=list)
    poor_quality_pages: list[int] = field(default_factory=list)

    def to_dict(self) -> dict:
        """Only the parts that fired. A consumer should be able to render
        "why" without filtering empties, and an all-empty dict is a
        meaningful "nothing was wrong"."""
        out: dict = {"requires_review": self.requires_review}
        if self.reasons:
            out["reasons"] = self.reasons
        if self.poor_quality_pages:
            # A DOCUMENT-level summary of the per-page flag, not a
            # duplicate of it. Consumers ask "does this document need
            # re-scanning?" and should not have to scan `pages[]` to find
            # out -- `poor_quality_pages` below then says which ones.
            #
            # Its absence was a real gap: `eval/run.py` scores the quality
            # flag from exactly this key, so with only the per-page flag
            # set it recorded 45 false negatives and 0.0 recall on the
            # corpus -- the gate looked calibrated-and-failing when in
            # fact it was reading a key nobody wrote.
            out["poor_image_quality"] = True
        if self.missing_mandatory:
            out["missing_mandatory"] = self.missing_mandatory
        if self.validation_failures:
            out["validation_failures"] = self.validation_failures
        if self.poor_quality_pages:
            out["poor_quality_pages"] = self.poor_quality_pages
        return out


MISSING_MANDATORY = "missing_mandatory_field"
VALIDATION_FAILED = "validation_failed"
POOR_IMAGE_QUALITY = "poor_image_quality"
FILE_REPAIRED = "file_repaired"


def decide(
    *,
    field_results=None,
    mandatory_keys: set[str] | None = None,
    validation_failures: list[str] | None = None,
    pages=None,
    file_repaired: bool = False,
) -> ReviewDecision:
    """Evaluate the rule.

    `field_results` is a list of `RequestedFieldResult`. A mandatory field
    counts as missing when its status is anything other than `extracted`
    -- `not_found` obviously, but `low_confidence` too: the whole point of
    that status is that nobody should rely on the value without looking.

    A mandatory field that was never *requested* is not a failure. The
    caller decides what is mandatory for the document type, and asking for
    review of a field this run was never told to extract would flag every
    document whose type has more fields than were requested.
    """
    reasons: list[str] = []
    missing: list[str] = []
    failures = list(validation_failures or [])
    poor_pages: list[int] = []

    if field_results and mandatory_keys:
        by_key = {r.field_key: r for r in field_results}
        for key in sorted(mandatory_keys):
            result = by_key.get(key)
            if result is None:
                continue  # never asked for -- see docstring
            # A field a document does not state cannot be "missing" from
            # it. `classification_level` is mandatory for INVOICE and is
            # chosen by us at filing time, so counting it here reported it
            # missing on 20 of 21 real documents and flagged every one.
            # It is still mandatory -- the manual save enforces it -- but
            # OCR is not the thing that was supposed to supply it.
            if result.status == "not_extractable":
                continue
            # `defaulted` is a real value (a tenant default applied where
            # the page carried no signal), so the field is not missing.
            # It is excluded from accuracy, not from completeness.
            if result.status not in ("extracted", "defaulted"):
                missing.append(key)

    for page in pages or []:
        flags = getattr(page, "flags", None) or {}
        if flags.get("poor_image_quality"):
            poor_pages.append(getattr(page, "page_number", 0))

    if missing:
        reasons.append(MISSING_MANDATORY)
    if failures:
        reasons.append(VALIDATION_FAILED)
    if poor_pages:
        reasons.append(POOR_IMAGE_QUALITY)
    if file_repaired:
        # Not one of 3.10's three, kept deliberately. PyMuPDF silently
        # repairs far more corruption than expected (50% truncation still
        # produces a completed result -- docs/ocr/failure-analysis.md #9),
        # and content recovered from a file that was not intact should not
        # be trusted like any other result.
        reasons.append(FILE_REPAIRED)

    return ReviewDecision(
        requires_review=bool(reasons),
        reasons=reasons,
        missing_mandatory=missing,
        validation_failures=failures,
        poor_quality_pages=poor_pages,
    )
