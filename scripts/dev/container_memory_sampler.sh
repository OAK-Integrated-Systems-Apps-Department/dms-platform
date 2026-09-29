#!/bin/sh
# Sample a container's memory while something else drives load at it.
#
# Phase 1 sized `mem_limit: 8704m` from `docker stats` sampled by hand
# every 3s over the 50-upload run (peak 6.89 GiB). Any step that changes
# how much raster the worker holds -- Step 4's resolution increase above
# all -- has to re-run that measurement, and doing it by hand again is
# both tedious and unreproducible.
#
# Reads the container's own cgroup v2 counters, which is what
# `docker stats` reads, so the numbers stay comparable with that 6.89 GiB
# figure. `memory.peak` is the kernel's own high-water mark and is the
# authoritative number; `memory.current` is sampled too so the shape over
# time is visible -- a steady climb and a single spike need different
# responses.
#
# Deliberately sh + docker rather than Python: it runs on the HOST (where
# the docker CLI is), and the host here has no Python. Every other dev
# script in this directory runs inside a container, where the opposite is
# true.
#
#   scripts/dev/container_memory_sampler.sh oakdms-ocr-worker 2 3600 \
#       docs/ocr/step4-memory.json "300dpi tiled, 50 uploads"
#
# Start it BEFORE the load, so the peak covers the whole run. It stops on
# the duration or on Ctrl-C, printing a JSON summary either way.
#
# NOTE: /sys/fs/cgroup is mounted read-only in these containers, so the
# peak reset fails and `peak_was_reset_first` comes back false -- the
# peak then also covers whatever the container did earlier. Restart the
# container before a measurement run to get a clean high-water mark
# (model warm-up is legitimately part of steady state and should be in
# it). The summary reports which case applied rather than quietly
# printing a number that is not about this test.
set -eu

# Git Bash/MSYS rewrites anything that looks like a POSIX path in an
# argument list into a Windows path, which turns `/sys/fs/cgroup/...`
# into `C:/Program Files/Git/sys/...` inside the container. Harmless
# everywhere else; required here.
export MSYS_NO_PATHCONV=1

CONTAINER="${1:-oakdms-ocr-worker}"
INTERVAL="${2:-2}"
DURATION="${3:-3600}"
OUT="${4:-}"
LABEL="${5:-}"

CG=/sys/fs/cgroup
read_counter() {
    docker exec "$CONTAINER" cat "$CG/$1" 2>/dev/null | head -1 | awk '{print $1}'
}

LIMIT=$(read_counter memory.max)
case "$LIMIT" in
    ''|max) echo "ERROR: cannot read $CONTAINER's memory.max -- is it running, and is the limit set?" >&2; exit 2 ;;
esac

# cgroup v2 on a recent kernel allows resetting the peak by writing to it.
# Where it is not writable the peak still works -- it just also covers
# whatever the container did before this run, which the summary says out
# loud rather than quietly reporting a number that is not about this test.
if docker exec "$CONTAINER" sh -c "echo 0 > $CG/memory.peak" 2>/dev/null; then
    RESET=true
else
    RESET=false
fi

SAMPLES=$(mktemp)
trap 'summarize' INT TERM
START=$(date +%s)

summarize() {
    # The kernel's high-water mark, and the largest value we actually
    # observed. The reported peak is the GREATER of the two.
    #
    # `memory.peak` alone is not safe: it is a property of the running
    # container, so it RESETS when the container is recreated. Sample
    # across a `docker compose up --force-recreate` -- which a measured
    # run routinely does, to get a clean starting peak -- and the counter
    # restarts while $SAMPLES still holds the old container's higher
    # readings. That produced a real, published summary with
    # `peak_gib 5.136` next to `p95_gib 5.521`, which is impossible: a
    # high-water mark cannot sit below an instantaneous value taken
    # inside the same window.
    #
    # Taking the max makes that unrepresentable. `peak_source` says which
    # one won, so a reset is visible in the output rather than silently
    # corrected -- if the samples win, the kernel counter was reset
    # mid-run and the window is not what the label claims.
    CGROUP_PEAK=$(read_counter memory.peak)
    MAX_SAMPLE=$(sort -n "$SAMPLES" 2>/dev/null | tail -1)
    : "${CGROUP_PEAK:=0}"
    : "${MAX_SAMPLE:=0}"
    if [ "$CGROUP_PEAK" -ge "$MAX_SAMPLE" ] 2>/dev/null; then
        PEAK=$CGROUP_PEAK
        PEAK_SOURCE=cgroup.memory.peak
    else
        PEAK=$MAX_SAMPLE
        PEAK_SOURCE=max_observed_sample
    fi
    ELAPSED=$(( $(date +%s) - START ))
    awk -v peak="$PEAK" -v limit="$LIMIT" -v reset="$RESET" -v elapsed="$ELAPSED" \
        -v interval="$INTERVAL" -v container="$CONTAINER" -v label="$LABEL" \
        -v peaksrc="$PEAK_SOURCE" -v cgpeak="$CGROUP_PEAK" '
        { v[NR] = $1 }
        END {
            n = NR
            for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (v[j] < v[i]) { t = v[i]; v[i] = v[j]; v[j] = t }
            gib = 1024 * 1024 * 1024
            med = n ? v[int((n + 1) / 2)] : 0
            p95 = n ? v[int(0.95 * (n - 1)) + 1] : 0
            printf "{\n"
            printf "  \"container\": \"%s\",\n  \"label\": \"%s\",\n", container, label
            printf "  \"samples\": %d,\n  \"interval_s\": %s,\n  \"duration_s\": %d,\n", n, interval, elapsed
            printf "  \"peak_gib\": %.3f,\n  \"peak_was_reset_first\": %s,\n", peak / gib, reset
            printf "  \"peak_source\": \"%s\",\n", peaksrc
            printf "  \"cgroup_peak_gib\": %.3f,\n", cgpeak / gib
            printf "  \"median_gib\": %.3f,\n  \"p95_gib\": %.3f,\n", med / gib, p95 / gib
            if (p95 > peak) printf "  \"INVARIANT_VIOLATED\": \"p95 exceeds peak\",\n"
            printf "  \"limit_gib\": %.3f,\n  \"headroom_gib\": %.3f,\n", limit / gib, (limit - peak) / gib
            printf "  \"utilisation\": %.4f\n}\n", peak / limit
        }' "$SAMPLES" | tee ${OUT:+"$OUT"}
    rm -f "$SAMPLES"
    exit 0
}

echo "sampling $CONTAINER every ${INTERVAL}s (limit $LIMIT bytes, peak reset: $RESET)" >&2
while [ $(( $(date +%s) - START )) -lt "$DURATION" ]; do
    C=$(read_counter memory.current) || true
    [ -n "${C:-}" ] && echo "$C" >> "$SAMPLES"
    sleep "$INTERVAL"
done
summarize
