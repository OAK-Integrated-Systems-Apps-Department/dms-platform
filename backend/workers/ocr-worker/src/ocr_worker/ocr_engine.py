from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass
class WordResult:
    text: str
    confidence: float
    bbox: tuple[int, int, int, int]


@dataclass
class OcrResult:
    text: str
    mean_confidence: float
    words: list[WordResult] = field(default_factory=list)


class OcrEngine(Protocol):
    def recognize(self, image: "Any", lang: str = "en") -> OcrResult: ...


class PaddleOcrEngine:
    """PaddleOCR is the character-recognition engine (OS-FR-001/002); Docling
    (layout.py) only ever hands this pixels for a page or a resolved
    region. The PaddleOCR model is loaded lazily on first use so importing
    this module -- or constructing this class in a test with a mock in its
    place -- never requires paddleocr/paddlepaddle to be installed.
    """

    def __init__(self):
        self._engines_by_lang: dict[str, "Any"] = {}

    def _engine_for(self, lang: str):
        if lang not in self._engines_by_lang:
            import os

            # PaddleX defaults to its oneDNN backend on CPU, which hits an
            # unimplemented op on the PP-OCRv6 detection model on this build
            # ("(Unimplemented) ConvertPirAttribute2RuntimeAttribute ...
            # onednn_instruction.cc", verified against the installed
            # version -- not specific to any one model flag, the plain
            # `paddle` run mode avoids it). Must be set before the first
            # `import paddlex`/`import paddleocr` anywhere in the process,
            # since it's read once at module load time.
            os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False")

            from paddleocr import PaddleOCR

            # PaddleOCR 3.x's constructor dropped the 2.x `use_angle_cls`/
            # `show_log` kwargs entirely (verified against the installed
            # version -- passing them raises "Unknown argument"). Doc
            # orientation classification and unwarping are also new
            # pipeline stages on top of the 2.x det+cls+rec flow, disabled
            # here because layout.py already renders pages at a known
            # orientation via PyMuPDF -- nothing to unwarp/reorient.
            # Real crash found live: with these left at PaddleX's own
            # defaults (None -> batch every detected text line through the
            # recognition/textline-orientation models in one single forward
            # pass), a normal, moderately dense scanned photo produced
            # dozens of detected lines batched at once, and CPU inference
            # over that batch peaked at 6-7GB RSS (verified via `docker
            # stats` polled every second through a real run) and OOM-killed
            # the whole worker process mid-request -- taking down every
            # other in-flight OCR job with it, not just this one. Capping
            # the batch size bounds peak memory to whatever one small batch
            # needs, trading a few extra sequential forward passes (a
            # handful of seconds) for the worker never dying mid-request.
            self._engines_by_lang[lang] = PaddleOCR(
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=True,
                text_recognition_batch_size=8,
                textline_orientation_batch_size=8,
                lang=lang,
            )
        return self._engines_by_lang[lang]

    def recognize(self, image, lang: str = "en") -> OcrResult:
        import numpy as np

        engine = self._engine_for(lang)
        result = engine.predict(np.array(image))

        words: list[WordResult] = []
        lines: list[str] = []
        confidences: list[float] = []

        # PaddleOCR 3.x's predict() returns a list of paddlex OCRResult
        # objects (one per input image), each dict-like with `rec_texts`/
        # `rec_scores`/`rec_boxes` (parallel lists, one entry per detected
        # text line) -- a different shape entirely from 2.x's `ocr()`,
        # which returned nested [[box, (text, confidence)], ...] tuples
        # (verified against the installed version).
        for page_result in result or []:
            texts = page_result["rec_texts"]
            scores = page_result["rec_scores"]
            boxes = page_result["rec_boxes"]
            for text, score, box in zip(texts, scores, boxes):
                confidence = float(score)
                bbox = (int(box[0]), int(box[1]), int(box[2]), int(box[3]))
                words.append(WordResult(text=text, confidence=confidence, bbox=bbox))
                lines.append(text)
                confidences.append(confidence)

        mean_confidence = sum(confidences) / len(confidences) if confidences else 0.0
        return OcrResult(text="\n".join(lines), mean_confidence=mean_confidence, words=words)
