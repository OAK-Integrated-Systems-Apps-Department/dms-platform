"""Every error code a reviewer can hit must have a sentence (Phase 4e).

The codes are defined here, in Python. The sentences live in dms-core's
`OcrErrorMessages`, in Java. Nothing links the two files, so adding a new
failure mode here and forgetting the message there is a one-line change
with no visible symptom -- the user just gets "OCR could not process this
document" for a problem we knew the exact cause of.

So this test reads the Java file. That is unusual, and deliberate: the
alternative is a Java test that reads errors.py, which has the same
awkwardness and catches the drift a release later, on the side that is
not changing.
"""
from __future__ import annotations

import re
from pathlib import Path


from ocr_worker import errors as errors_mod

_JAVA = (
    Path(__file__).resolve().parents[3]
    / "dms-core/src/main/java/com/oak/dms/ocrworker/OcrErrorMessages.java"
)


def _codes_in_python() -> set[str]:
    """Every `code` on an OcrEngineError subclass, read off the classes
    themselves rather than by grepping -- a code assigned any other way
    still has to be covered."""
    codes = set()
    base = errors_mod.OcrEngineError
    for name in dir(errors_mod):
        value = getattr(errors_mod, name)
        if isinstance(value, type) and issubclass(value, base):
            code = getattr(value, "code", None)
            if isinstance(code, str) and code:
                codes.add(code)
    return codes


def _messages_in_java() -> dict[str, str]:
    source = _JAVA.read_text(encoding="utf-8")
    return dict(re.findall(r'Map\.entry\(\s*"([A-Z_]+)"\s*,\s*"([^"]+)"\s*\)', source))


def test_the_java_side_is_where_we_think_it_is():
    """If dms-core moves this file, the coverage test below would pass
    vacuously against an empty map. Fail loudly instead."""
    assert _JAVA.exists(), f"OcrErrorMessages.java not found at {_JAVA}"
    assert _messages_in_java(), "parsed no messages -- has the map's shape changed?"


def test_every_error_code_has_a_friendly_message():
    missing = sorted(_codes_in_python() - set(_messages_in_java()))

    assert not missing, (
        f"{missing} have no friendly message in OcrErrorMessages.java. "
        f"A reviewer hitting one of these gets the generic fallback for a "
        f"failure whose exact cause we already know."
    )


def test_no_message_leaks_an_internal_detail():
    forbidden = ("Exception", "Traceback", "paddle", "docling", "fitz", "psycopg", "None")
    for code, message in _messages_in_java().items():
        for token in forbidden:
            assert token not in message, f"{code}'s message mentions {token!r}"


def test_each_message_is_a_sentence_a_person_could_read():
    for code, message in sorted(_messages_in_java().items()):
        assert message[0].isupper(), f"{code}: does not start as a sentence"
        assert message.endswith((".", "!")), f"{code}: does not end as a sentence"
        # Long enough to say something, short enough to fit on a queue row.
        assert 20 <= len(message) <= 120, f"{code}: {len(message)} characters"
        assert code not in message, f"{code}: the message repeats the raw code"
