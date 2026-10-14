"""`ContentUnavailable` must survive a round trip through pickle.

pebble's process pool pickles an exception to carry it from a worker
subprocess back to the parent process. The default pickle reduction for an
exception calls `type(exc)(*exc.args)`, and `args` here is only ever
`(message,)` (`status` lives on `self`, set outside of `super().__init__`),
so unpickling used to raise its own `TypeError` ("missing 1 required
positional argument") instead of the real error - which crashed the whole
process pool (`BrokenProcessPool`) for every job in flight, not just the
one that hit a real, recoverable refusal from dms-core (401/403/404/5xx).
"""
from __future__ import annotations

import pickle

from ocr_worker.content_client import ContentUnavailable


def test_survives_a_pickle_round_trip_with_status_and_message_intact():
    original = ContentUnavailable(401, "dms-core refused the rendition for version X: HTTP 401")

    restored = pickle.loads(pickle.dumps(original))

    assert restored.status == 401
    assert str(restored) == str(original)


def test_retryable_still_works_after_a_round_trip():
    restored = pickle.loads(pickle.dumps(ContentUnavailable(503, "upstream unavailable")))
    assert restored.retryable is True

    restored = pickle.loads(pickle.dumps(ContentUnavailable(401, "not authorised")))
    assert restored.retryable is False
