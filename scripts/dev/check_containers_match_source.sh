#!/bin/sh
# Fail loudly when a running container is older than the source tree.
#
# This has cost three measured runs, in three different ways:
#
#   * a repository.py edit landed after the image's source layer was
#     copied, so the container ran code one line different from the tree;
#   * a whole phase of worker changes was measured against an image built
#     before them;
#   * V40 silently did not apply, because Flyway migrations are packaged
#     inside the dms-core JAR and the image predated the file -- the stack
#     came up healthy and the constraint simply was not there.
#
# Each time the run looked fine and the numbers were about the wrong code.
# This turns that into a refusal.
#
#     scripts/dev/check_containers_match_source.sh
#
# Exit 0 everything matches, 1 something is stale, 2 the check could not
# run. Stale is a hard failure, not a warning -- a warning is effectively
# what the previous three runs already had.
#
# Deliberately sh + docker rather than Python, for the same reason as
# container_memory_sampler.sh: this runs on the HOST, where the docker CLI
# lives, and the host here has no Python.
set -eu

# Git Bash/MSYS rewrites POSIX-looking arguments into Windows paths, which
# mangles every in-container path below.
export MSYS_NO_PATHCONV=1

REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORKER_SRC="$REPO/backend/workers/ocr-worker/src/ocr_worker"
MIGRATIONS="$REPO/backend/dms-core/src/main/resources/db/migration"
OCR_CONTAINER="${OCR_CONTAINER:-oakdms-ocr-worker}"
PG_CONTAINER="${PG_CONTAINER:-oakdms-postgres}"

PROBLEMS=$(mktemp); LOCAL=$(mktemp); REMOTE=$(mktemp); APPLIED=$(mktemp)
trap 'rm -f "$PROBLEMS" "$LOCAL" "$REMOTE" "$APPLIED" 2>/dev/null || true' EXIT

running() {
    [ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]
}

for c in "$OCR_CONTAINER" "$PG_CONTAINER"; do
    if ! running "$c"; then
        echo "SKIP: $c is not running" >&2
        exit 2
    fi
done

# `md5sum` prints "<hash>  <name>", and on Git Bash the name carries a
# leading `*` (binary mode) while find adds `./`. Both sides are put
# through the same normaliser so a local `*./config.py` and a remote
# `./config.py` compare equal -- without it every file reported as BOTH
# missing and extra.
normalise() {
    awk '{ n = $2; sub(/^\*/, "", n); sub(/^\.\//, "", n); print n, $1 }' | sort
}

( cd "$WORKER_SRC" && find . -name '*.py' -not -path '*__pycache__*' -exec md5sum {} + ) \
    | normalise > "$LOCAL"

# One exec, not one per file: ~30 files is ~30 round trips otherwise, and
# this is meant to be cheap enough to run before every measured run.
docker exec "$OCR_CONTAINER" sh -c \
    "cd /app/src/ocr_worker && find . -name '*.py' -not -path '*__pycache__*' -exec md5sum {} +" \
    2>/dev/null | normalise > "$REMOTE" || true

# An empty listing means the comparison did not happen. Reporting OK on
# it would be the exact failure this script exists to prevent -- and it
# did happen once, when a broken awk silently emptied both sides.
if [ ! -s "$LOCAL" ]; then
    echo "CHECK FAILED: found no source files under $WORKER_SRC" >&2
    exit 2
fi
if [ ! -s "$REMOTE" ]; then
    echo "CHECK FAILED: could not list $OCR_CONTAINER's source -- comparison not performed" >&2
    exit 2
fi

join -j 1 -o 0,1.2,2.2 "$LOCAL" "$REMOTE" | while read -r name a b; do
    [ "$a" = "$b" ] || echo "$OCR_CONTAINER: STALE    $name" >> "$PROBLEMS"
done
join -j 1 -v 1 "$LOCAL" "$REMOTE" | while read -r name _; do
    echo "$OCR_CONTAINER: MISSING  $name (in source, not in the image)" >> "$PROBLEMS"
done
join -j 1 -v 2 "$LOCAL" "$REMOTE" | while read -r name _; do
    echo "$OCR_CONTAINER: EXTRA    $name (in the image, deleted from source)" >> "$PROBLEMS"
done

# Migrations are compared against the DATABASE, not against the JAR. What
# matters is not "is it in the image" but "has it run" -- those differ
# whenever an image is rebuilt without the container being recreated,
# which is exactly the case that bit us with V40.
docker exec "$PG_CONTAINER" psql -U postgres -d dms_core_db -t -A \
    -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank" \
    2>/dev/null | tr -d ' \r' | sort > "$APPLIED" || true

if [ ! -s "$APPLIED" ]; then
    echo "CHECK FAILED: could not read flyway_schema_history from $PG_CONTAINER" >&2
    exit 2
fi

for f in "$MIGRATIONS"/V*__*.sql; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    version=$(echo "$base" | sed 's/^V\([0-9][0-9]*\)__.*/\1/')
    grep -qx "$version" "$APPLIED" || echo "dms-core: NOT APPLIED  $base" >> "$PROBLEMS"
done

if [ ! -s "$PROBLEMS" ]; then
    echo "OK: $(wc -l < "$LOCAL" | tr -d ' ') worker source files match, and every migration is applied."
    exit 0
fi

echo "STALE CONTAINERS -- measurements taken now would be about the wrong code."
echo
sort -u "$PROBLEMS" | sed 's/^/  /'
cat <<'EOM'

Rebuild and recreate before running anything you intend to believe:
    cd infra/local && docker compose build ocr-worker dms-core \
        && docker compose up -d --force-recreate ocr-worker dms-core
EOM
exit 1
