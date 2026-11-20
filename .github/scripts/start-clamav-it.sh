#!/usr/bin/env bash
# Starts the throwaway clamd the ClamAV integration tests need (see the `integration-clamav` job in ci.yml and the six
# classes it runs). Kept apart from start-it-services.sh: that script starts Keycloak and OpenBao, which these tests do
# not use, and ClamAV is memory-hungry enough that it must not share a job with them.
#
# What was checked against the image's own sources (Cisco-Talos/clamav-docker, clamav/1.4/debian), not assumed:
#   * clamav/clamav-debian:1.4.3 exists on Docker Hub (linux/amd64). The non-_base tags are built with Dockerfile.add-db,
#     which runs `freshclam` at build time, so a signature database ships inside the image.
#   * The entrypoint (/init) applies CLAMD_CONF_<Key>=<value> to /etc/clamav/clamd.conf, honours CLAMAV_NO_FRESHCLAMD and
#     CLAMD_STARTUP_TIMEOUT, and runs a foreground freshclam before clamd only if /var/lib/clamav/main.cvd is missing.
#   * The image's health check, clamdcheck.sh, connects to localhost:3310 inside the container and expects PONG to PING.
#   * None of the clamd.conf keys set below is active in the image's stock config: the 1.4.3 clamd.conf.sample has each
#     one exactly once, commented ("#Key value"), and the entrypoint rewrites that line in place, so no key is duplicated.
#
# Readiness is more than PING/PONG: the script scans an ordinary line of text (must be clean) and the EICAR test string
# (must be detected) over the published port, using the same INSTREAM framing as dms-core's ClamdClient.
#
# Signatures: the database baked into the image is used as it is, and the freshclam daemon is switched off. These tests
# need clamd to detect the EICAR test string and to call ordinary files clean, nothing about current malware, and a daemon
# that downloads and reloads signatures in the middle of a run would only add pauses (ConcurrentDatabaseReload is off to
# keep memory at one signature set, so a reload blocks scans). If main.cvd were missing the entrypoint downloads the full
# set itself before clamd starts; the readiness wait below is long enough to cover that. /var/lib/clamav is deliberately
# not cached: nothing here writes to it, and a cache restored from an untrusted pull request could substitute signatures.
#
# Writes CLAMAV_HOST and CLAMAV_PORT as KEY=value lines to $IT_ENV_FILE (default: $GITHUB_ENV in Actions). Runs locally too
# (pick a free port, e.g. CLAMAV_IT_PORT=13310).
set -euo pipefail

IMAGE="${CLAMAV_IT_IMAGE:-clamav/clamav-debian:1.4.3}"
NAME="${IT_CLAMAV_CONTAINER:-dms-it-clamav}"
PORT="${CLAMAV_IT_PORT:-13310}"          # host side; clamd listens on 3310 inside the container
MEMORY="${CLAMAV_IT_MEMORY:-3g}"         # ClamAV documents 3 GiB minimum / 4 GiB preferred
TIMEOUT_S="${CLAMAV_IT_TIMEOUT_S:-600}"  # bound for the whole startup, signature download included
OUT="${IT_ENV_FILE:-${GITHUB_ENV:?set IT_ENV_FILE or run in GitHub Actions}}"

diagnostics() {
  {
    echo "---- docker ps -a"
    docker ps -a || true
    echo "---- container state"
    docker inspect -f 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} health={{if .State.Health}}{{.State.Health.Status}}{{end}}' "$NAME" || true
    echo "---- last 200 lines of the container log (entrypoint output)"
    docker logs --tail 200 "$NAME" || true
    echo "---- last 100 lines of clamd's own log (it logs to a file, not to the container log)"
    docker exec "$NAME" tail -n 100 /var/log/clamav/clamd.log || true
    echo "---- /var/lib/clamav"
    docker exec "$NAME" ls -la /var/lib/clamav || true
    echo "---- container memory"
    docker stats --no-stream "$NAME" || true
    echo "---- runner memory"
    free -m || true
  } >&2
}
fail() {
  echo "::error::$1"
  diagnostics
  exit 1
}

# 127.0.0.1 only: the published port is not reachable from outside the runner.
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" \
  --memory "$MEMORY" --memory-swap "$MEMORY" \
  -p "127.0.0.1:${PORT}:3310" \
  -e CLAMAV_NO_FRESHCLAMD=true \
  -e CLAMD_STARTUP_TIMEOUT="$TIMEOUT_S" \
  -e CLAMD_CONF_ConcurrentDatabaseReload=no \
  -e CLAMD_CONF_StreamMaxLength=2047M \
  -e CLAMD_CONF_MaxFileSize=2047M \
  -e CLAMD_CONF_MaxScanSize=4000M \
  -e CLAMD_CONF_AlertExceedsMax=yes \
  -e CLAMD_CONF_AlertEncryptedArchive=yes \
  -e CLAMD_CONF_MaxScanTime=900000 \
  "$IMAGE" >/dev/null || fail "could not start $IMAGE on 127.0.0.1:${PORT}"

# Ready = the container is still running AND clamd answers PING with PONG (the image's own check), within the bound.
ready=false
for ((waited = 0; waited < TIMEOUT_S; waited += 3)); do
  [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || echo false)" = "true" ] \
    || fail "the ClamAV container stopped before clamd was ready"
  if docker exec "$NAME" clamdcheck.sh >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 3
done
$ready || fail "clamd did not answer PING within ${TIMEOUT_S}s"

# A signature database must be present (clamd refuses to start without one; this makes the failure explicit).
docker exec "$NAME" sh -c 'ls /var/lib/clamav/main.c[vl]d /var/lib/clamav/daily.c[vl]d >/dev/null' \
  || fail "no signature database in /var/lib/clamav"

# From here on everything goes through the published port from the runner itself - the path the tests use. The framing
# is the one dms-core's own ClamdClient speaks: a NUL-terminated command, then (for INSTREAM) 4-byte big-endian length +
# data chunks ended by a zero-length chunk, and a NUL-terminated reply.
clamd_command() { # command-with-NUL-as-printf-format -> reply on stdout
  local reply=""
  exec 3<>"/dev/tcp/127.0.0.1/${PORT}" || return 1
  printf "$1" >&3
  IFS= read -r -d '' -t 20 reply <&3 || true
  exec 3>&- 3<&-
  printf '%s' "$reply"
}
clamd_scan() { # payload -> reply on stdout (one INSTREAM chunk)
  local payload="$1" len reply=""
  len=${#payload}
  exec 3<>"/dev/tcp/127.0.0.1/${PORT}" || return 1
  printf 'zINSTREAM\0' >&3
  printf "\\x$(printf '%02x' $(( (len >> 24) & 255 )))\\x$(printf '%02x' $(( (len >> 16) & 255 )))\\x$(printf '%02x' $(( (len >> 8) & 255 )))\\x$(printf '%02x' $(( len & 255 )))" >&3
  printf '%s' "$payload" >&3
  printf '\x00\x00\x00\x00' >&3
  IFS= read -r -d '' -t 20 reply <&3 || true
  exec 3>&- 3<&-
  printf '%s' "$reply"
}

reply="$(clamd_command 'zPING\0')"
[ "$reply" = "PONG" ] || fail "127.0.0.1:${PORT} answered '${reply}' instead of PONG"

# PONG only says the daemon is up. Prove it can actually scan: ordinary text must come back clean and the EICAR test
# string (a harmless, standard anti-virus test file, detected from the baked signatures) must be detected.
clean="$(clamd_scan 'a perfectly ordinary line of text')"
[ "$clean" = "stream: OK" ] || fail "clamd did not report an ordinary text clean (reply: '${clean}')"
# Built from fragments so the complete 68-byte test string never sits in this file (some anti-virus tools flag any file
# that contains it); the concatenation is exactly the standard EICAR test string.
EICAR='X5O!P%@AP[4\PZX5'
EICAR+='4(P^)7CC)7}$EI'
EICAR+='CAR-STANDARD-ANTI'
EICAR+='VIRUS-TEST-'
EICAR+='FILE!$H+H*'
found="$(clamd_scan "$EICAR")"
case "$found" in
  "stream: "*" FOUND") ;;
  *) fail "clamd did not detect the EICAR test string (reply: '${found}')" ;;
esac
echo "scan check passed: ordinary text -> '${clean}', EICAR -> '${found}'"

# Signature freshness is reported, not enforced: the database is the one baked into the image, and these tests need
# EICAR detection, not current coverage; clamd here scans test data only. A very old image is worth a warning, though.
echo "clamd: $(clamd_command 'zVERSION\0')"
newest="$(docker exec "$NAME" sh -c 'stat -c %Y /var/lib/clamav/*.c[vl]d | sort -n | tail -n 1' 2>/dev/null || echo 0)"
if [ "${newest:-0}" -gt 0 ]; then
  age_days=$(( ($(date +%s) - newest) / 86400 ))
  echo "newest signature file is ${age_days} day(s) old"
  [ "$age_days" -le 365 ] || echo "::warning::the ClamAV signatures in ${IMAGE} are ${age_days} days old; bump the image tag"
fi

# 127.0.0.1, not "localhost": the container publishes IPv4 only and a name can resolve to ::1 first.
printf '%s=%s\n' CLAMAV_HOST 127.0.0.1 >> "$OUT"
printf '%s=%s\n' CLAMAV_PORT "$PORT" >> "$OUT"
echo "ClamAV ($IMAGE) is ready on 127.0.0.1:${PORT}"
