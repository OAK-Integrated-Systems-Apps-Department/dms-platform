"""Phase 3: per-cause retry budgets in the reaper.

The reaper used to decide on `attempts < max_delivery_attempts` and
nothing else -- every death treated as transient. For a document that is
simply too large or too slow it never is, and the cost was measured: the
50-upload concurrent run at `OCR_RENDER_MAX_MP=4.0` spent roughly 40 of
its 60 minutes retrying one 35-page scan five times, each attempt burning
the full 935s budget before dying in exactly the same way.

Each test drives a real cause to its real end state. The thing that
matters is not "did it retry the right number of times" on its own -- it
is that the version always reaches a **terminal** state with a code
dms-core can turn into a user-facing message. A version left `processing`
is the failure mode Phase 1's exit criteria name by hand.
"""

from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.models import DocumentVersion, ProcessingJob  # noqa: E402
from ocr_worker.reaper import Reaper  # noqa: E402
from ocr_worker.repository import OcrRepository  # noqa: E402


def _queue_job(session_factory, seeded_version):
    with session_factory() as session:
        OcrRepository(session).ensure_queued_job(
            tenant_id=seeded_version["tenant_id"],
            version_id=seeded_version["version_id"],
            correlation_id=None,
        )
        session.commit()


def _expire_with_cause(session_factory, version_id, *, attempts: int, cause: str) -> None:
    """A crashed worker: the job is `processing`, its lease has expired,
    and `last_error` holds whatever cause the parent managed to record."""
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=version_id).one()
        job.status = "processing"
        job.attempts = attempts
        job.lease_expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        job.last_error = cause
        session.commit()


def _state(session_factory, version_id):
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=version_id).one()
        version = session.get(DocumentVersion, version_id)
        return job.status, job.attempts, job.last_error, version.ocr_status


def _drive_to_terminal(session_factory, settings, version_id, cause: str, *, limit: int = 12) -> int:
    """Reap repeatedly, re-expiring the lease each round, until the job
    stops being retried. Returns the number of requeues.

    Deliberately a loop rather than a single tick: the question is how
    many attempts a cause is worth *in total*, and a per-tick assertion
    would pass even if the budget never terminated at all.
    """
    reaper = Reaper(settings, session_factory)
    requeues = 0
    attempts = 1
    for _ in range(limit):
        status = _state(session_factory, version_id)[0]
        if status in ("dead_letter", "failed", "completed"):
            break
        _expire_with_cause(session_factory, version_id, attempts=attempts, cause=cause)
        reaper._reap_once()
        if _state(session_factory, version_id)[0] == "queued":
            requeues += 1
            attempts += 1
    return requeues


@pytest.mark.parametrize(
    "cause,code,expected_requeues",
    [
        ("TIMEOUT: exceeded the 85s budget for this document", "TIMEOUT", 1),
        ("ENCRYPTED_PDF: password_protected.pdf is password-protected", "ENCRYPTED_PDF", 0),
        ("CORRUPT_FILE: could not open as pdf", "CORRUPT_FILE", 0),
        ("UNSUPPORTED_FORMAT: application/x-nonsense", "UNSUPPORTED_FORMAT", 0),
        ("TOO_MANY_PAGES: 900 pages exceeds ocr_max_pages=50", "TOO_MANY_PAGES", 0),
        ("FILE_TOO_LARGE: 900MB exceeds the limit", "FILE_TOO_LARGE", 0),
    ],
)
def test_each_cause_gets_its_own_budget_then_dead_letters(
    session_factory, seeded_version, cause, code, expected_requeues
):
    settings = Settings(db_url="sqlite://")
    version_id = seeded_version["version_id"]
    _queue_job(session_factory, seeded_version)

    requeues = _drive_to_terminal(session_factory, settings, version_id, cause)

    assert requeues == expected_requeues, f"{code} should be retried {expected_requeues} time(s)"
    status, _, last_error, ocr_status = _state(session_factory, version_id)
    assert status == "dead_letter"
    assert ocr_status == "failed"        # no version may remain 'processing'
    assert last_error.startswith(code)   # a code dms-core can map to a message


def test_a_timeout_is_not_retried_five_times(session_factory, seeded_version):
    """The specific regression. A timeout caused by the document's own
    size or page count repeats identically on every attempt, so four
    further attempts buy nothing and cost a pool worker each."""
    settings = Settings(db_url="sqlite://")
    version_id = seeded_version["version_id"]
    _queue_job(session_factory, seeded_version)

    requeues = _drive_to_terminal(
        session_factory, settings, version_id, "TIMEOUT: exceeded the 935s budget"
    )

    assert requeues == 1
    assert settings.max_delivery_attempts == 5  # the old budget, still the fallback
    assert _state(session_factory, version_id)[0] == "dead_letter"


def test_a_version_that_crashes_a_worker_twice_is_dead_lettered_immediately(
    session_factory, seeded_version
):
    """WORKER_CRASH gets 3 retries because a crash genuinely can be
    transient -- an OOM caused by a NEIGHBOUR's page, not this one. But a
    version that has died twice is not unlucky, it is too big for this
    worker at this configuration, and the message has to say so rather
    than leaving an operator to infer it from a retry count."""
    settings = Settings(db_url="sqlite://")
    version_id = seeded_version["version_id"]
    _queue_job(session_factory, seeded_version)
    crash = "WORKER_CRASH: pool worker died with exitcode=-9 while processing this document"

    _expire_with_cause(session_factory, version_id, attempts=1, cause=crash)
    Reaper(settings, session_factory)._reap_once()
    assert _state(session_factory, version_id)[0] == "queued"  # first crash: retried

    _expire_with_cause(session_factory, version_id, attempts=2, cause=crash)
    Reaper(settings, session_factory)._reap_once()

    status, _, last_error, ocr_status = _state(session_factory, version_id)
    assert status == "dead_letter"
    assert ocr_status == "failed"
    assert last_error.startswith("WORKER_CRASH")
    assert "exceeded worker memory" in last_error


def test_an_unknown_cause_gets_the_same_budget_as_a_crash(session_factory, seeded_version):
    """"No cause recorded" is very often a crash one level up.

    A container-level OOM kills the PARENT too, so nothing survives to
    write `WORKER_CRASH:` and the job arrives here with a blank
    `last_error` -- the leaked path, one level up from the one
    `_record_death_cause` closes. The reaper already dead-letters such a
    job labelled `WORKER_CRASH`, so its budget has to match that label:
    otherwise the same physical failure is retried a different number of
    times depending only on whether the process that would have described
    it also died.
    """
    settings = Settings(db_url="sqlite://")
    version_id = seeded_version["version_id"]
    _queue_job(session_factory, seeded_version)

    requeues = _drive_to_terminal(session_factory, settings, version_id, "")

    assert requeues == settings.retry_attempts_by_code["WORKER_CRASH"]
    assert requeues != settings.max_delivery_attempts - 1, "must not silently keep the old budget"
    status, _, last_error, ocr_status = _state(session_factory, version_id)
    assert (status, ocr_status) == ("dead_letter", "failed")
    # Not an invented "UNKNOWN:" prefix -- dms-core maps codes to
    # user-facing messages and an unregistered one falls through to raw
    # text in front of a user.
    assert last_error.startswith("WORKER_CRASH")


def test_an_unrecognized_code_in_last_error_is_treated_as_unknown(session_factory, seeded_version):
    """Defensive: a `last_error` that does not start with a registered
    code must not be mistaken for one, or a future message format change
    would silently re-budget every failure."""
    settings = Settings(db_url="sqlite://")
    version_id = seeded_version["version_id"]
    _queue_job(session_factory, seeded_version)

    requeues = _drive_to_terminal(
        session_factory, settings, version_id, "something went wrong, no code here"
    )

    assert requeues == settings.retry_attempts_by_code["WORKER_CRASH"]


# ----------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------
def test_the_policy_is_parsed_and_validated():
    settings = Settings(db_url="sqlite://")
    assert settings.retry_attempts_by_code["TIMEOUT"] == 1
    assert settings.retry_attempts_by_code["ENCRYPTED_PDF"] == 0
    assert settings.retry_attempts_by_code["WORKER_CRASH"] == 3
    # Retries vs total attempts: two units that are an off-by-one apart,
    # kept separate on purpose.
    assert settings.retries_allowed_for("TIMEOUT") == 1
    assert settings.max_attempts_for("TIMEOUT") == 2
    # Unknown inherits WORKER_CRASH's budget, not max_delivery_attempts.
    assert settings.max_attempts_for(None) == settings.max_attempts_for("WORKER_CRASH") == 4
    assert settings.retries_allowed_for(None) is None  # no policy of its own


@pytest.mark.parametrize(
    "value,expected_message",
    [
        ("TIMEOUT", "not CODE=attempts"),
        ("NOT_A_CODE=1", "unknown error code"),
        ("TIMEOUT=banana", "is not an integer"),
        ("TIMEOUT=-1", "cannot be negative"),
    ],
)
def test_a_malformed_policy_fails_loudly_rather_than_being_ignored(value, expected_message):
    """Skipping a bad entry with a warning would mean a deployment with a
    typo silently falls back to retrying everything five times -- exactly
    the behaviour this setting exists to stop, and nobody would notice
    until a queue backed up."""
    settings = Settings(db_url="sqlite://", ocr_retry_attempts_by_code=value)
    with pytest.raises(ValueError) as caught:
        _ = settings.retry_attempts_by_code
    assert expected_message in str(caught.value)


def test_an_empty_policy_falls_back_to_the_old_behaviour():
    """With no policy at all -- including no WORKER_CRASH entry to inherit
    -- everything reverts to the single pre-Phase-3 budget, so clearing
    the setting is a real rollback rather than a new set of numbers."""
    settings = Settings(db_url="sqlite://", ocr_retry_attempts_by_code="")
    assert settings.retry_attempts_by_code == {}
    assert settings.max_attempts_for("TIMEOUT") == settings.max_delivery_attempts
    assert settings.max_attempts_for(None) == settings.max_delivery_attempts
