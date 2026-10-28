from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from .config import Settings


def make_session_factory(settings: Settings) -> sessionmaker[Session]:
    engine = create_engine(settings.db_url, pool_size=settings.db_pool_size, pool_pre_ping=True)
    return sessionmaker(bind=engine, expire_on_commit=False)


@contextmanager
def session_scope(session_factory: sessionmaker[Session]) -> Iterator[Session]:
    """One transaction per call -- callers commit implicitly on clean exit,
    everything rolls back together on any exception. This is what makes the
    'ocr_outputs + document_versions + processing_jobs + outbox_events in a
    single DB transaction' requirement (workflow step 6) trivial to satisfy:
    every write in the block goes through the same Session/transaction.
    """
    session = session_factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
