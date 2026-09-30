#!/usr/bin/env bash
# Operator tool for the DMS OpenBao (runbook: docs/secrets-openbao.md).
#
#   bash bao-ops.sh up                  start the OpenBao container
#   bash bao-ops.sh status              initialized / sealed / unseal progress
#   bash bao-ops.sh init                FIRST TIME ONLY: create unseal keys + root token
#   bash bao-ops.sh unseal              enter unseal keys (after every OpenBao restart)
#   bash bao-ops.sh configure [--init-file F] [--reset-ops-password]
#                                       (root token) KV engine, audit log, policies,
#                                       GitHub OIDC deploy role, operator login
#   bash bao-ops.sh import <env-file>   copy the secrets listed in secret-keys.txt into OpenBao
#   bash bao-ops.sh verify <env-file>   check OpenBao holds exactly the same values
#   bash bao-ops.sh strip  <env-file>   remove those secrets from the file (after verify)
#   bash bao-ops.sh set <KEY>           change one secret (hidden prompt; applied on next deploy)
#   bash bao-ops.sh render              write the RAM-only secrets file for a manual deploy
#   bash bao-ops.sh clear               delete that file again
#   bash bao-ops.sh snapshot <out-file> raft backup of all OpenBao data (encrypted at rest)
#
# Needs only curl and python3 on the host - no bao CLI install. Tokens and
# passwords are read with hidden prompts and passed via stdin/process
# substitution, never on a command line (so never in `ps` or shell history).
# BAO_TOKEN may be exported instead of the operator login prompt.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROD="$HERE/../production"
KEYS_FILE="$PROD/secret-keys.txt"
BAO_ADDR="${BAO_ADDR:-http://127.0.0.1:8200}"
KV_PATH="secret/data/dms/production/app"
SECRETS_DIR="${BAO_SECRETS_DIR:-/dev/shm/oakdms}"
SECRETS_FILE="$SECRETS_DIR/secrets.env"
OPS_USER="${BAO_OPS_USER:-ops}"
GITHUB_REPO="${GITHUB_REPOSITORY:-OAK-Integrated-Systems-Apps-Department/dms-platform}"

log()  { printf '[bao-ops] %s\n' "$*"; }
fail() { printf '[bao-ops] ERROR: %s\n' "$*" >&2; exit 1; }

# raw_curl OUTFILE METHOD PATH  -> prints the HTTP status. The token header
# is fed through process substitution inline, so it never appears in argv.
raw_curl() {
  local out="$1" method="$2" path="$3"
  local args=(-sS -o "$out" -w '%{http_code}' -X "$method")
  if [ "$method" != GET ] && [ "$method" != DELETE ]; then
    args+=(-H 'Content-Type: application/json' --data-binary @-)
  fi
  if [ -n "${BAO_TOKEN:-}" ]; then
    curl "${args[@]}" -H @<(printf 'X-Vault-Token: %s\n' "$BAO_TOKEN") "$BAO_ADDR/v1/$path"
  else
    curl "${args[@]}" "$BAO_ADDR/v1/$path"
  fi
}

# probe METHOD PATH -> 0 if 2xx, 1 otherwise. Never exits the script.
probe() {
  local out status; out="$(mktemp)"
  status="$(raw_curl "$out" "$1" "$2" </dev/null)" || status=000
  rm -f "$out"
  [ "${status:0:1}" = 2 ]
}

# api METHOD PATH [json-body-on-stdin]  -> response body on stdout; any
# non-2xx is fatal (with OpenBao's own error message).
api() {
  local method="$1" path="$2" status body
  body="$(mktemp)"
  status="$(raw_curl "$body" "$method" "$path")" || { rm -f "$body"; fail "cannot reach OpenBao at $BAO_ADDR"; }
  if [ "${status:0:1}" != 2 ]; then
    local msg; msg="$(python3 -c 'import json,sys; print("; ".join(json.load(sys.stdin).get("errors") or []))' <"$body" 2>/dev/null || true)"
    rm -f "$body"; fail "$method /v1/$path -> HTTP $status ${msg:+($msg)}"
  fi
  cat "$body"; rm -f "$body"
}

json_get() { python3 -c 'import json,sys; d=json.load(sys.stdin)
for k in sys.argv[1].split("."): d=d[k]
print(d if not isinstance(d,bool) else str(d).lower())' "$1"; }

seal_status() { api GET sys/seal-status; }
require_unsealed() {
  local s; s="$(seal_status)"
  [ "$(json_get initialized <<<"$s")" = true ] || fail "OpenBao is not initialized - run: bash bao-ops.sh init"
  [ "$(json_get sealed <<<"$s")" = false ] || fail "OpenBao is SEALED - run: bash bao-ops.sh unseal"
}

# Pasted tokens/keys often carry a Windows CR or stray spaces - never valid in either.
strip_ws() { printf '%s' "$1" | tr -d '[:space:]'; }

secret_keys() { grep -vE '^\s*(#|$)' "$KEYS_FILE" | tr -d '\r'; }

# Reads KEY=VALUE for the secret keys from an env file (BOM/CRLF tolerant,
# optional surrounding quotes) and prints a JSON object. Values never echo.
env_to_json() {
  python3 - "$1" "$KEYS_FILE" <<'PY'
import json, sys
env_path, keys_path = sys.argv[1], sys.argv[2]
keys = [l.strip() for l in open(keys_path, encoding="utf-8") if l.strip() and not l.lstrip().startswith("#")]
vals = {}
for line in open(env_path, encoding="utf-8-sig"):
    line = line.rstrip("\r\n")
    if not line or line.lstrip().startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    k = k.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        v = v[1:-1]
    vals[k] = v
missing = [k for k in keys if k not in vals]
if missing:
    sys.exit("missing in " + env_path + ": " + ", ".join(missing))
bad = [k for k in keys if vals[k] == "CHANGE_ME"]
if bad:
    sys.exit("still CHANGE_ME in " + env_path + ": " + ", ".join(bad))
print(json.dumps({k: vals[k] for k in keys}))
PY
}

# Operator login (userpass) unless BAO_TOKEN is already exported.
login() {
  [ -n "${BAO_TOKEN:-}" ] && return 0
  local pw; read -rsp "OpenBao password for '$OPS_USER': " pw; echo
  BAO_TOKEN="$(printf '{"password":%s}' "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$pw")" \
    | api POST "auth/userpass/login/$OPS_USER" | json_get auth.client_token)"
  unset pw
  export BAO_TOKEN
  trap 'api POST auth/token/revoke-self </dev/null >/dev/null 2>&1 || true' EXIT
}

cmd="${1:-}"; shift || true
case "$cmd" in
  up)
    docker compose -f "$HERE/docker-compose.yml" up -d
    ;;

  status)
    s="$(seal_status)"
    log "initialized: $(json_get initialized <<<"$s")  sealed: $(json_get sealed <<<"$s")  unseal progress: $(json_get progress <<<"$s")/$(json_get t <<<"$s")"
    ;;

  init)
    s="$(seal_status)"
    [ "$(json_get initialized <<<"$s")" = false ] || fail "already initialized - init is a one-time operation"
    r="$(printf '{"secret_shares":3,"secret_threshold":2}' | api PUT sys/init)"
    cat <<EOF

==========================================================================
 OpenBao initialized. These are shown ONCE and are NOT stored anywhere.
 Give each unseal key to a DIFFERENT trusted person (password manager).
 Any 2 of the 3 unseal OpenBao. Losing 2 = losing every secret for good.
 NEVER paste them into chat, tickets, email or git.
==========================================================================
EOF
    # The keys exist nowhere else - if pretty-printing ever fails, print the
    # raw response instead rather than lose them.
    if ! python3 -c 'import json, sys
d = json.load(sys.stdin)
for i, k in enumerate(d["keys_base64"], 1):
    print(" Unseal key %d: %s" % (i, k))
print("")
print(" Initial root token: " + d["root_token"])
print(" (use it once for: bash bao-ops.sh configure - it is revoked at the end)")' <<<"$r"; then
      echo " Could not format the output - raw OpenBao response (keys_base64 = unseal keys):"
      printf '%s\n' "$r"
    fi
    echo "=========================================================================="
    log "next: bash bao-ops.sh unseal"
    ;;

  unseal)
    while :; do
      s="$(seal_status)"
      [ "$(json_get sealed <<<"$s")" = false ] && { log "unsealed"; break; }
      read -rsp "Unseal key ($(json_get progress <<<"$s")/$(json_get t <<<"$s") entered): " k; echo
      k="$(strip_ws "$k")"
      printf '{"key":%s}' "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$k")" | api PUT sys/unseal >/dev/null
      unset k
    done
    ;;

  configure)
    require_unsealed
    reset_ops=0; init_file=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --reset-ops-password) reset_ops=1 ;;
        --init-file) init_file="${2:?--init-file needs a path}"; shift ;;
        *) fail "unknown configure option: $1" ;;
      esac
      shift
    done
    if [ -n "$init_file" ]; then
      # Read the root token straight from the saved `init` output - no
      # copy/paste in between to corrupt it.
      [ -f "$init_file" ] || fail "$init_file not found"
      grep -q "already initialized" "$init_file" && fail "$init_file holds a REFUSED init (OpenBao was already initialized) - it has no valid token"
      BAO_TOKEN="$(awk '/Initial root token:/ {print $4; exit}' "$init_file")"
      [ -n "$BAO_TOKEN" ] || fail "no 'Initial root token:' line in $init_file"
      log "root token: read from $init_file"
    else
      read -rsp "Root token: " BAO_TOKEN; echo
    fi
    BAO_TOKEN="$(strip_ws "$BAO_TOKEN")"; export BAO_TOKEN
    # Fail with a clear message instead of a bare 403 on the first real call.
    probe GET auth/token/lookup-self || fail "that is not a valid token (paste only the value after 'Initial root token:', not the label or an unseal key)"

    mounts="$(api GET sys/mounts)"
    if python3 -c 'import json,sys; sys.exit(0 if "secret/" in json.load(sys.stdin) else 1)' <<<"$mounts"; then
      log "kv-v2 at secret/: already enabled"
    else
      printf '{"type":"kv","options":{"version":"2"}}' | api POST sys/mounts/secret >/dev/null; log "kv-v2 at secret/: enabled"
    fi

    # Declared in config.hcl (OpenBao 2.x refuses API-enabled audit devices).
    audits="$(api GET sys/audit)"
    if python3 -c 'import json,sys; sys.exit(0 if "file/" in json.load(sys.stdin) else 1)' <<<"$audits"; then
      log "audit log: active (/openbao/logs/audit.log, declared in config.hcl)"
    else
      fail "audit device missing - config.hcl must declare audit \"file\" \"file\"; restart OpenBao and unseal"
    fi

    for p in dms-deploy dms-operator; do
      python3 -c 'import json,sys; print(json.dumps({"policy": open(sys.argv[1]).read()}))' "$HERE/policies/$p.hcl" \
        | api PUT "sys/policies/acl/$p" >/dev/null
      log "policy $p: written"
    done

    auths="$(api GET sys/auth)"
    if ! python3 -c 'import json,sys; sys.exit(0 if "jwt/" in json.load(sys.stdin) else 1)' <<<"$auths"; then
      printf '{"type":"jwt"}' | api POST sys/auth/jwt >/dev/null
    fi
    printf '{"oidc_discovery_url":"https://token.actions.githubusercontent.com","bound_issuer":"https://token.actions.githubusercontent.com"}' \
      | api POST auth/jwt/config >/dev/null
    # Only a deploy job of THIS repo, in the "production" environment, from
    # main, gets a token - and only a 10-minute, read-one-path one.
    python3 -c 'import json,sys; print(json.dumps({
      "role_type": "jwt", "user_claim": "sub", "bound_audiences": ["openbao"],
      "bound_claims": {"repository": sys.argv[1], "environment": "production", "ref": "refs/heads/main"},
      "token_policies": ["dms-deploy"], "token_ttl": "10m", "token_max_ttl": "15m"}))' "$GITHUB_REPO" \
      | api POST auth/jwt/role/dms-deploy >/dev/null
    log "GitHub OIDC deploy role: $GITHUB_REPO / environment=production / ref=main"

    if ! python3 -c 'import json,sys; sys.exit(0 if "userpass/" in json.load(sys.stdin) else 1)' <<<"$auths"; then
      printf '{"type":"userpass"}' | api POST sys/auth/userpass >/dev/null
    fi
    if probe GET "auth/userpass/users/$OPS_USER" && [ "$reset_ops" = 0 ]; then
      log "operator login '$OPS_USER': exists (use --reset-ops-password to change it)"
    else
      read -rsp "New password for operator '$OPS_USER' (min 16 chars): " p1; echo
      read -rsp "Repeat: " p2; echo
      [ "$p1" = "$p2" ] || fail "passwords differ"
      [ "${#p1}" -ge 16 ] || fail "use at least 16 characters"
      python3 -c 'import json,sys; print(json.dumps({"password": sys.argv[1], "token_policies": ["dms-operator"], "token_ttl": "1h", "token_max_ttl": "4h"}))' "$p1" \
        | api POST "auth/userpass/users/$OPS_USER" >/dev/null
      unset p1 p2
      log "operator login '$OPS_USER': set"
    fi

    read -rp "Revoke the root token now (recommended)? [Y/n] " yn
    if [ "${yn:-Y}" != n ] && [ "${yn:-Y}" != N ]; then
      api POST auth/token/revoke-self </dev/null >/dev/null; log "root token revoked"
    fi
    ;;

  import)
    f="${1:?usage: bao-ops.sh import <env-file>}"; [ -f "$f" ] || fail "$f not found"
    require_unsealed; login
    body="$(env_to_json "$f")" || fail "import aborted - nothing was written"
    python3 -c 'import json,sys; print(json.dumps({"data": json.load(sys.stdin)}))' <<<"$body" | api POST "$KV_PATH" >/dev/null
    unset body
    log "imported $(secret_keys | wc -l) secrets into $KV_PATH:"; secret_keys | sed 's/^/  /'
    ;;

  verify)
    f="${1:?usage: bao-ops.sh verify <env-file>}"; [ -f "$f" ] || fail "$f not found"
    require_unsealed; login
    local_json="$(env_to_json "$f")"
    api GET "$KV_PATH" | python3 -c 'import json,sys
remote=json.load(sys.stdin)["data"]["data"]; local=json.loads(sys.argv[1])
diff=[k for k in local if remote.get(k)!=local[k]]
print("[bao-ops] all %d secrets match" % len(local) if not diff else "[bao-ops] DIFFERENT: " + ", ".join(diff))
sys.exit(1 if diff else 0)' "$local_json"
    unset local_json
    ;;

  strip)
    f="${1:?usage: bao-ops.sh strip <env-file>}"; [ -f "$f" ] || fail "$f not found"
    bash "$0" verify "$f" || fail "not stripping: OpenBao does not hold the same values"
    read -rp "Remove $(secret_keys | wc -l) secret lines from $f (OpenBao keeps them)? [y/N] " yn
    [ "$yn" = y ] || [ "$yn" = Y ] || { log "nothing changed"; exit 0; }
    tmp="$(mktemp "$(dirname "$f")/.strip.XXXXXX")"
    python3 - "$f" "$KEYS_FILE" "$tmp" <<'PY'
import sys
env_path, keys_path, out = sys.argv[1:4]
keys = {l.strip() for l in open(keys_path, encoding="utf-8") if l.strip() and not l.lstrip().startswith("#")}
lines = open(env_path, encoding="utf-8-sig").read().splitlines()
kept = [l for l in lines if l.split("=", 1)[0].strip() not in keys]
open(out, "w", encoding="utf-8", newline="\n").write(
    "\n".join(kept) + "\n# Secrets (see secret-keys.txt) live in OpenBao: secret/dms/production/app\n")
PY
    chmod 600 "$tmp"; mv "$tmp" "$f"
    log "stripped - $f now holds configuration only"
    ;;

  set)
    key="${1:?usage: bao-ops.sh set <KEY>   (one of secret-keys.txt)}"
    secret_keys | grep -qxF "$key" || fail "$key is not in secret-keys.txt"
    require_unsealed; login
    read -rsp "New value for $key: " v1; echo
    read -rsp "Repeat: " v2; echo
    [ "$v1" = "$v2" ] || fail "values differ - nothing changed"
    [ -n "$v1" ] || fail "empty value - nothing changed"
    case "$v1" in *"'"*|*$'\n'*) fail "value must not contain a single quote or newline";; esac
    # PATCH changes only this key; every other secret keeps its value.
    body="$(KEY="$key" VAL="$v1" python3 -c 'import json,os; print(json.dumps({"data": {os.environ["KEY"]: os.environ["VAL"]}}))')"
    unset v1 v2
    status="$(printf '%s' "$body" | curl -sS -o /dev/null -w '%{http_code}' -X PATCH \
      -H 'Content-Type: application/merge-patch+json' -H @<(printf 'X-Vault-Token: %s\n' "$BAO_TOKEN") \
      --data-binary @- "$BAO_ADDR/v1/$KV_PATH")"
    unset body
    [ "${status:0:1}" = 2 ] || fail "PATCH failed (HTTP $status)"
    log "$key updated in OpenBao. It takes effect on the next deploy (Actions -> Deploy -> Run workflow)."
    log "Database/Keycloak/RabbitMQ passwords must ALSO be changed in that service first - see docs/secrets-openbao.md."
    ;;

  render)
    require_unsealed; login
    install -d -m 700 "$SECRETS_DIR"
    tmp="$(mktemp "$SECRETS_DIR/.render.XXXXXX")"
    api GET "$KV_PATH" | python3 "$PROD/render-secrets.py" "$KEYS_FILE" >"$tmp" || { rm -f "$tmp"; fail "render failed"; }
    chmod 600 "$tmp"; mv "$tmp" "$SECRETS_FILE"
    log "rendered $(secret_keys | wc -l) secrets to $SECRETS_FILE (RAM). Run deploy.sh, then: bash bao-ops.sh clear"
    ;;

  clear)
    rm -f "$SECRETS_FILE"; log "removed $SECRETS_FILE"
    ;;

  snapshot)
    out="${1:?usage: bao-ops.sh snapshot <out-file>}"
    require_unsealed; login
    ( umask 077; api GET sys/storage/raft/snapshot >"$out" )
    log "snapshot written to $out ($(wc -c <"$out") bytes; encrypted with the unseal keys - store it OFF this server)"
    ;;

  ""|-h|--help)
    sed -n '2,24p' "$0"
    ;;
  *)
    fail "unknown command: $cmd (see --help)"
    ;;
esac
