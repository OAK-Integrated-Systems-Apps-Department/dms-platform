from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

# The three routes a page can take (production prompt section 3.6).
NATIVE_TEXT = "native_text"
OCR = "paddleocr"
HYBRID = "hybrid"


# `(cid:NN)` is the signature of a PDF whose font has no usable ToUnicode
# map -- the extractor knows a glyph is there and cannot say which
# character it is. U+FFFD is the same failure after a lossy decode.
# Together they are the most common shape of "there is a text layer and
# it is worthless", which is precisely the case the old routing rule
# could not see.
_CID_RE = re.compile(r"\(cid:\d+\)")
_REPLACEMENT_CHAR = "�"

# A token that could plausibly be a word in a Latin-script document:
# letters, with the apostrophes and hyphens real words carry.
_WORD_RE = re.compile(r"[^\W\d_][\w'’-]*", re.UNICODE)


@dataclass(frozen=True)
class RouteDecision:
    """What was decided for one page, and every number the decision was
    made from.

    The metrics are carried, not just the verdict, because the verdict
    alone is unfalsifiable in a bug report. "This invoice came back empty"
    is answerable when the page says it routed `native_text` at a garbage
    ratio of 0.02 and 91% text coverage; it is not answerable from the
    word `native_text` on its own.
    """

    route: str
    reason: str
    text_coverage: float = 0.0
    image_coverage: float = 0.0
    uncovered_image_coverage: float = 0.0
    garbage_ratio: float = 0.0
    plausibility: float = 1.0
    native_chars: int = 0
    ocr_regions: list = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "route": self.route,
            "reason": self.reason,
            "text_coverage": round(self.text_coverage, 4),
            "image_coverage": round(self.image_coverage, 4),
            "uncovered_image_coverage": round(self.uncovered_image_coverage, 4),
            "garbage_ratio": round(self.garbage_ratio, 4),
            "plausibility": round(self.plausibility, 4),
            "native_chars": self.native_chars,
            "ocr_regions": len(self.ocr_regions),
        }


def garbage_ratio(text: str) -> float:
    """Fraction of this text that is known-unreadable rather than content.

    Counts `(cid:NN)` runs, U+FFFD, and control/unassigned characters,
    against the total length. Whitespace is excluded from the denominator
    so that a page of `(cid:12) (cid:13) ...` -- which is half spaces --
    does not score 0.5 and slip under a 0.6 threshold.
    """
    if not text:
        return 0.0
    bad = sum(len(match.group(0)) for match in _CID_RE.finditer(text))
    remainder = _CID_RE.sub("", text)
    for char in remainder:
        if char == _REPLACEMENT_CHAR:
            bad += 1
        elif unicodedata.category(char) in ("Cc", "Cf", "Co", "Cn") and char not in "\t\n\r":
            bad += 1
    total = sum(1 for char in text if not char.isspace())
    return min(bad / total, 1.0) if total else 0.0


def plausibility(text: str) -> float:
    """Fraction of word-like tokens that look like real words.

    **DEVIATION from production prompt section 3.6**, which asks for "the
    ratio of dictionary-like tokens for the configured languages". This
    is a shape heuristic, not a dictionary lookup. Recorded as a
    deviation rather than presented as the spec, because it is a weaker
    signal than what was asked for and a future reader should know that
    before trusting it.

    Why, in order of weight:

    1. **A dictionary would be wrong on the documents this system
       handles.** A real invoice is mostly `INV-2026-05426`, `PO-74300`,
       `CC-4450`, `Contoso Supplies PLC`, `LKR`, `80gsm` -- identifiers,
       vendor names, part codes and units. None is in a dictionary, all
       are correct, and a dictionary-ratio metric would score a perfectly
       good text layer as implausible and send it to be re-OCR'd. That is
       the expensive direction of this decision: re-OCRing a good page
       trades exact text for a guess.
    2. **It would have to be per-language and shipped.** `ocr_languages`
       is configurable and Tamil is a stated target (section 3.7); a
       word list per configured language is a packaging, sizing and
       versioning problem for a check that is a tie-breaker, not a
       primary signal.
    3. **The failure it exists to catch is not subtle.** The real case is
       a scanner junk layer, which `garbage_ratio` already catches on
       `(cid:NN)`. This is the second line for junk that is *not* cid-
       shaped -- mojibake, a mis-decoded encoding -- where shape is
       enough: such text has no vowels in plausible places.

    What it costs: this cannot detect a text layer that is well-formed
    but *wrong* (a correct-looking OCR pass that read the wrong
    document), which a dictionary could not detect either. And it is
    tuned on synthetic fixtures -- see "provisional" in
    docs/ocr/phase2-verification.md.

    Returns 1.0 for text with no word-like tokens at all (a page of pure
    numbers is not implausible, it is just not words), so this metric can
    only ever accuse text that genuinely looks like letters.
    """
    tokens = [token for token in _WORD_RE.findall(text) if len(token) >= 3]
    if not tokens:
        return 1.0
    good = 0
    for token in tokens:
        lowered = token.lower()
        has_vowel = any(char in "aeiouy" for char in lowered)
        # A run of 5+ consonants is not a word in any language this
        # engine is configured for; it is what glyph soup looks like.
        longest_consonant_run = max(
            (len(run) for run in re.findall(r"[^aeiouy\W\d_]+", lowered)), default=0
        )
        if has_vowel and longest_consonant_run < 5:
            good += 1
    return good / len(tokens)


def _area(box) -> float:
    if box is None:
        return 0.0
    x0, y0, x1, y1 = box
    return max(0.0, x1 - x0) * max(0.0, y1 - y0)


def _intersection(a, b):
    ax0, ay0, ax1, ay1 = a
    bx0, by0, bx1, by1 = b
    x0, y0 = max(ax0, bx0), max(ay0, by0)
    x1, y1 = min(ax1, bx1), min(ay1, by1)
    if x1 <= x0 or y1 <= y0:
        return None
    return (x0, y0, x1, y1)


def _covered_area(region, boxes) -> float:
    """Area of `region` covered by `boxes`, counting overlap once.

    Uses a coordinate-compression sweep rather than summing box areas,
    because text boxes on a real page overlap constantly (a line box and
    its table cell, a heading and its paragraph). Summing would report
    coverage above 1.0 and make a text-covered region look like an
    uncovered image.
    """
    clipped = [box for box in (_intersection(region, b) for b in boxes) if box is not None]
    if not clipped:
        return 0.0
    xs = sorted({x for box in clipped for x in (box[0], box[2])})
    ys = sorted({y for box in clipped for y in (box[1], box[3])})
    total = 0.0
    for xi in range(len(xs) - 1):
        for yi in range(len(ys) - 1):
            cell = (xs[xi], ys[yi], xs[xi + 1], ys[yi + 1])
            cx = (cell[0] + cell[2]) / 2
            cy = (cell[1] + cell[3]) / 2
            if any(b[0] <= cx <= b[2] and b[1] <= cy <= b[3] for b in clipped):
                total += _area(cell)
    return total


def decide_route(
    *,
    native_text: str,
    native_boxes: list,
    image_boxes: list,
    page_box,
    max_garbage_ratio: float,
    min_plausibility: float,
    min_uncovered_image_coverage: float,
) -> RouteDecision:
    """Per-page routing, replacing "any native text at all -> native_text".

    That old rule cost two whole conditions of the eval corpus, both at
    0.0% field accuracy:

    - `garbage_text_layer`: a scan carrying an INVISIBLE `(cid:NN)` text
      layer, which is what a bad scanner-OCR pass leaves behind. A reader
      sees a clean page; the router saw a text layer, took the native
      path, and returned confident nonsense.
    - `hybrid_page`: a typed header over a scanned body. The header made
      the page "native", so PaddleOCR never ran and the entire body --
      every field on it -- was silently lost.

    The order of the checks matters. Garbage is tested before coverage,
    because a junk layer also produces junk *boxes*, and those boxes
    would otherwise be counted as covering the image and hide the very
    page that most needs OCR.
    """
    page_area = _area(page_box)
    if page_area <= 0:
        return RouteDecision(route=OCR, reason="page has no usable geometry")

    stripped = (native_text or "").strip()
    if not stripped:
        return RouteDecision(
            route=OCR,
            reason="no native text layer",
            image_coverage=min(_covered_area(page_box, image_boxes) / page_area, 1.0),
        )

    # Note there is deliberately NO "too few characters to trust" rule.
    # An earlier version had one and it was wrong in both directions: it
    # re-OCR'd genuinely sparse native pages (a cover sheet, a title
    # page), throwing away exact text for a guess; and it was redundant,
    # because the case it was aimed at -- a scan whose text layer is a
    # stamped page number -- is caught better below as HYBRID, which
    # keeps the page number AND reads the scan.

    ratio = garbage_ratio(stripped)
    plausible = plausibility(stripped)
    text_coverage = _covered_area(page_box, native_boxes) / page_area
    image_coverage = _covered_area(page_box, image_boxes) / page_area

    if ratio > max_garbage_ratio:
        return RouteDecision(
            route=OCR,
            reason=f"text layer is {ratio:.0%} unreadable glyphs",
            text_coverage=text_coverage, image_coverage=image_coverage,
            garbage_ratio=ratio, plausibility=plausible, native_chars=len(stripped),
        )

    if plausible < min_plausibility:
        return RouteDecision(
            route=OCR,
            reason=f"only {plausible:.0%} of the text layer's words look like words",
            text_coverage=text_coverage, image_coverage=image_coverage,
            garbage_ratio=ratio, plausibility=plausible, native_chars=len(stripped),
        )

    # A good text layer. Is there also a large image region it does not
    # explain? That is the hybrid page.
    uncovered_regions = []
    uncovered_area = 0.0
    for image_box in image_boxes:
        area = _area(image_box)
        if area <= 0:
            continue
        covered = _covered_area(image_box, native_boxes)
        if (area - covered) / area > 0.5:
            uncovered_regions.append(image_box)
            uncovered_area += area - covered
    uncovered_coverage = min(uncovered_area / page_area, 1.0)

    if uncovered_coverage >= min_uncovered_image_coverage:
        return RouteDecision(
            route=HYBRID,
            reason=f"{uncovered_coverage:.0%} of the page is image with no text layer over it",
            text_coverage=text_coverage, image_coverage=image_coverage,
            uncovered_image_coverage=uncovered_coverage,
            garbage_ratio=ratio, plausibility=plausible, native_chars=len(stripped),
            ocr_regions=uncovered_regions,
        )

    return RouteDecision(
        route=NATIVE_TEXT,
        reason="good text layer covering the page",
        text_coverage=text_coverage, image_coverage=image_coverage,
        uncovered_image_coverage=uncovered_coverage,
        garbage_ratio=ratio, plausibility=plausible, native_chars=len(stripped),
    )
