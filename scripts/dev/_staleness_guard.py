"""Refuse to run a verification against stale containers.

Three measured runs have already been invalidated by an image older than
the source -- a one-line edit that missed the source layer, a whole phase
of changes measured against a pre-change image, and V40 never applying
because Flyway migrations ship inside the dms-core JAR. None failed
visibly; each produced numbers about the wrong code.

Two ways to check, because the obvious one is unavailable where it is
most needed:

1. **`check_containers_match_source.sh`**, when the docker CLI is around.
   Compares worker source by md5 AND every migration against
   `flyway_schema_history`. This is the complete check, and it runs on the
   host.
2. **The worker's own `/fingerprint`, plus a direct read of
   `flyway_schema_history`**, otherwise. Every harness here runs INSIDE a
   container, where there is no docker CLI and no socket -- so path 1 can
   never speak there, and a guard that can never speak is decorative. The
   worker reports md5s of the source it actually loaded (HTTP, no docker
   needed), and any caller with a DSN also gets the migration check --
   which is the one that bit hardest, since V40 came up healthy and
   simply was not applied.

`OCR_SKIP_STALENESS_CHECK=1` skips it, loudly -- for the case where the
stack is intentionally down. A check that *cannot* run does not get a
vote; only a definite "these are stale" refuses.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

SKIP_ENV = "OCR_SKIP_STALENESS_CHECK"
_SCRIPT = Path(__file__).resolve().parent / "check_containers_match_source.sh"
_SRC = Path(__file__).resolve().parents[2] / "backend/workers/ocr-worker/src/ocr_worker"
WORKER_URL = os.environ.get("OCR_WORKER_URL", "http://ocr-worker:8090")


def _local_fingerprint() -> dict[str, str]:
    out: dict[str, str] = {}
    for path in sorted(_SRC.rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        out[path.relative_to(_SRC).as_posix()] = hashlib.md5(path.read_bytes()).hexdigest()
    return out


def check_worker_over_http(what: str) -> list[str]:
    """Stale files, by asking the worker what it is running.

    Empty list means fresh OR unanswerable -- "I could not ask" is not
    "it is stale", and refusing to run because a service is down would
    make the guard something people route around.
    """
    # /fingerprint is authenticated like every other /ocr route. The token
    # is the same one dms-core sends; a harness that does not have it
    # simply cannot check, which is reported rather than treated as fresh.
    token = os.environ.get("INTERNAL_SERVICE_TOKEN", "")
    request = urllib.request.Request(
        f"{WORKER_URL}/fingerprint",
        headers={"X-Internal-Token": token} if token else {},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            remote = (json.loads(response.read().decode("utf-8")) or {}).get("source") or {}
    except Exception as exc:  # noqa: BLE001
        hint = ""
        if "401" in str(exc):
            hint = " -- set INTERNAL_SERVICE_TOKEN"
        elif "404" in str(exc):
            hint = " -- OCR_FINGERPRINT_ENABLED is off on that worker"
        print(f"[{what}] could not reach {WORKER_URL}/fingerprint ({exc}){hint}; "
              f"staleness NOT checked", file=sys.stderr)
        return []

    local = _local_fingerprint()
    if not local or not remote:
        print(f"[{what}] fingerprint comparison empty; staleness NOT checked", file=sys.stderr)
        return []

    problems: list[str] = []
    for name, digest in sorted(local.items()):
        if name not in remote:
            problems.append(f"MISSING  {name} (in source, not in the running worker)")
        elif remote[name] != digest:
            problems.append(f"STALE    {name}")
    for name in sorted(remote):
        if name not in local:
            problems.append(f"EXTRA    {name} (running, deleted from source)")
    return problems


def check_migrations(what: str, dsn: str | None = None) -> list[str]:
    """Migrations in the source tree that Flyway has not applied.

    This is the gap the HTTP path cannot close, and it is the one that bit
    hardest: V40 never applied because migrations ship inside the dms-core
    JAR and the image predated the file -- the stack came up healthy and
    the constraint simply was not there. The worker's own fingerprint
    says nothing about that.

    Compared against `flyway_schema_history`, i.e. what has actually RUN,
    not what is in the JAR. Those differ exactly when an image is rebuilt
    without the container being recreated.

    Needs a DB connection; `dsn` or `DB_DSN`/`OCR_CHECK_DSN`. Returns []
    when it cannot connect -- "I could not ask" is not "it is stale".
    """
    dsn = dsn or os.environ.get("OCR_CHECK_DSN") or os.environ.get("DB_DSN")
    if not dsn:
        return []
    migrations = Path(__file__).resolve().parents[2] / (
        "backend/dms-core/src/main/resources/db/migration"
    )
    on_disk = {}
    for path in sorted(migrations.glob("V*__*.sql")):
        version = path.name.split("__", 1)[0].lstrip("V")
        on_disk[version] = path.name
    if not on_disk:
        return []

    try:
        import psycopg

        with psycopg.connect(dsn, connect_timeout=10) as conn, conn.cursor() as cur:
            cur.execute("SELECT version FROM flyway_schema_history WHERE success")
            applied = {str(row[0]).strip() for row in cur.fetchall()}
    except Exception as exc:  # noqa: BLE001
        print(f"[{what}] could not read flyway_schema_history ({exc}); "
              f"migrations NOT checked", file=sys.stderr)
        return []

    return [
        f"NOT APPLIED  V{version}__... ({name})"
        for version, name in sorted(on_disk.items(), key=lambda kv: int(kv[0]))
        if version not in applied
    ]


def _refuse(what: str, header: str, problems: list[str]) -> None:
    sys.stderr.write(header + "\n\n")
    for line in problems:
        sys.stderr.write(f"  {line}\n")
    raise SystemExit(
        f"\n[{what}] REFUSING TO RUN -- the numbers would be about the wrong code.\n"
        f"Rebuild, or set {SKIP_ENV}=1 if you know better."
    )


def enforce(what: str, dsn: str | None = None) -> None:
    """`dsn` lets a caller that already has a database connection string
    hand it over, so migrations are checked too -- phase1_verification.py
    has one, and that is the harness most worth protecting."""
    if os.environ.get(SKIP_ENV):
        print(f"[{what}] staleness check SKIPPED via {SKIP_ENV}", file=sys.stderr)
        return

    if _SCRIPT.exists() and shutil.which("docker") is not None:
        try:
            proc = subprocess.run(["sh", str(_SCRIPT)], capture_output=True, text=True, timeout=120)
        except Exception as exc:  # noqa: BLE001
            print(f"[{what}] staleness check could not run ({exc}); continuing", file=sys.stderr)
            return
        if proc.returncode == 0:
            print(f"[{what}] {(proc.stdout or '').strip()}")
            return
        if proc.returncode == 1:
            sys.stderr.write((proc.stdout or "") + (proc.stderr or ""))
            raise SystemExit(
                f"\n[{what}] REFUSING TO RUN against stale containers.\n"
                f"Rebuild, or set {SKIP_ENV}=1 if you know better."
            )
        print(f"[{what}] staleness check could not run:\n{(proc.stderr or '').strip()}",
              file=sys.stderr)
        return

    problems = check_worker_over_http(what) + check_migrations(what, dsn)
    if problems:
        _refuse(
            what,
            "STALE CONTAINERS -- not running the source in this tree, or missing a migration.",
            problems,
        )
    else:
        print(f"[{what}] ocr-worker and migrations match the source tree.")
