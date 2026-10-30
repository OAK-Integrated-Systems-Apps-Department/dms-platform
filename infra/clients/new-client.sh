#!/usr/bin/env bash
# Sets up a new client installation in one go. Run it on your own machine, from
# the repository.
#
#   infra/clients/new-client.sh --name acme \
#       --docker-host 10.0.0.5 --public-url https://dms.acme.example \
#       [--nginx-host 10.0.0.2] [--ssh-user oak] [--deploy-path /opt/dms] \
#       [--runner self-hosted] [--out DIR] [--use-env FILE] \
#       [--provision] [--github [--repo owner/name] [--reviewers login1,login2]]
#       [--cleanup] [--no-register] [--force]
#
# Always (local, nothing leaves this machine):
#   - the server's .env, with a fresh random value for every secret
#   - a deploy SSH key pair (ed25519, no passphrase) for the GitHub environment
#   - an nginx site file for the client (upstreams point at the Docker host when
#     nginx is on a different server)
#   - an entry in infra/clients.yml (skip with --no-register)
# All written to --out (default ~/.dms-clients/<name>, mode 700, outside the repo).
#
# --provision  prepares the Docker server over SSH: shows its host key for you to
#              confirm, installs the deploy key (you type the SSH password once,
#              into ssh itself - this script never sees it), creates the install
#              directory and copies the .env there with mode 600.
# --github     creates the GitHub environment client-<name> and sets its
#              variables and secrets with the gh CLI (needs `gh auth login`).
#              Without gh, the values to enter by hand are printed.
#
# --reviewers GitHub logins who must approve a deploy to this client (set on the
#              environment; needs --github and gh).
# --cleanup    once the .env is on the server and the key is in GitHub, delete the
#              local copies of both (the server's .env is then the only one). Needs
#              --provision and --github with gh installed.
#
# It never stores a password, and refuses to overwrite an existing .env or key
# unless --force. See docs/multi-client-deployment.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLIENTS_FILE="${CLIENTS_FILE:-$ROOT/infra/clients.yml}"
NGINX_TEMPLATE="$ROOT/infra/production/nginx/dms.conf"

die() { echo "error: $*" >&2; exit 1; }
say() { printf '\n== %s\n' "$*"; }

NAME= DOCKER_HOST_ADDR= NGINX_HOST= PUBLIC_URL= SSH_USER=oak DEPLOY_PATH=/opt/dms
RUNNER=self-hosted OUT= USE_ENV= PROVISION=0 GITHUB=0 REPO= REGISTER=1 FORCE=0 REVIEWERS= CLEANUP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --docker-host) DOCKER_HOST_ADDR="$2"; shift 2 ;;
    --nginx-host) NGINX_HOST="$2"; shift 2 ;;
    --public-url) PUBLIC_URL="$2"; shift 2 ;;
    --ssh-user) SSH_USER="$2"; shift 2 ;;
    --deploy-path) DEPLOY_PATH="$2"; shift 2 ;;
    --runner) RUNNER="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --use-env) USE_ENV="$2"; shift 2 ;;
    --provision) PROVISION=1; shift ;;
    --github) GITHUB=1; shift ;;
    --repo) REPO="$2"; shift 2 ;;
    --reviewers) REVIEWERS="$2"; shift 2 ;;
    --cleanup) CLEANUP=1; shift ;;
    --no-register) REGISTER=0; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) awk 'NR>1 && /^#/ {print; next} NR>1 {exit}' "$0"; exit 0 ;;
    *) die "unknown option $1 (try --help)" ;;
  esac
done

[[ "$NAME" =~ ^[a-z0-9][a-z0-9-]*$ ]] || die "--name: lowercase letters, digits and '-'"
[ -n "$DOCKER_HOST_ADDR" ] || die "--docker-host is required"
[[ "$PUBLIC_URL" =~ ^https?://[^/]+$ ]] || die "--public-url must look like http(s)://host (no path)"
[[ "$DEPLOY_PATH" =~ ^/[A-Za-z0-9._/-]+$ ]] || die "--deploy-path must be an absolute path"
[[ "$SSH_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || die "--ssh-user looks wrong"
[ -f "$NGINX_TEMPLATE" ] || die "run this from the repository (missing $NGINX_TEMPLATE)"
[ -z "$REVIEWERS" ] || [[ "$REVIEWERS" =~ ^[A-Za-z0-9-]+(,[A-Za-z0-9-]+)*$ ]] || die "--reviewers: comma-separated GitHub logins"
if [ "$CLEANUP" = 1 ]; then
  { [ "$PROVISION" = 1 ] && [ "$GITHUB" = 1 ] && command -v gh >/dev/null 2>&1; } \
    || die "--cleanup needs --provision and --github, and the gh CLI installed (otherwise the local copies are your only ones)"
fi

ENV_NAME="client-$NAME"
OUT="${OUT:-$HOME/.dms-clients/$NAME}"
PUBLIC_HOST="${PUBLIC_URL#*://}"
SEPARATE_NGINX=0
if [ -n "$NGINX_HOST" ] && [ "$NGINX_HOST" != "$DOCKER_HOST_ADDR" ]; then
  SEPARATE_NGINX=1
  [[ "$DOCKER_HOST_ADDR" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "with nginx on another server, --docker-host must be an IPv4 address (it becomes PROXY_BIND_ADDRESS)"
fi

umask 077
mkdir -p "$OUT"; chmod 700 "$OUT" 2>/dev/null || true
guard() { [ ! -e "$1" ] || [ "$FORCE" = 1 ] || die "$1 already exists - keeping it (use --force to replace)"; }

# ---- 1. the server's .env ------------------------------------------------------
say "1/5  .env for the server"
if [ -n "$USE_ENV" ]; then
  [ -f "$USE_ENV" ] || die "--use-env: $USE_ENV not found"
  guard "$OUT/env"; cp "$USE_ENV" "$OUT/env"; echo "using your existing $USE_ENV"
else
  guard "$OUT/env"
  # The first interpreter that actually runs: on Windows `python3` is often a
  # Microsoft Store stub that exists but does nothing.
  PY=""
  for candidate in python3 python py; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(0)' >/dev/null 2>&1; then PY="$candidate"; break; fi
  done
  [ -n "$PY" ] || die "a working python 3 is needed (python3, python or py)"
  bind=(); [ "$SEPARATE_NGINX" = 1 ] && bind=(--bind-address "$DOCKER_HOST_ADDR")
  "$PY" "$ROOT/infra/clients/render_env.py" --name "$NAME" --public-url "$PUBLIC_URL" --out "$OUT/env" "${bind[@]}"
fi
chmod 600 "$OUT/env" 2>/dev/null || true
echo "wrote $OUT/env"

# ---- 2. deploy key ---------------------------------------------------------------
say "2/5  deploy SSH key"
if [ -e "$OUT/deploy-key" ] && [ "$FORCE" != 1 ]; then echo "keeping existing $OUT/deploy-key"
else rm -f "$OUT/deploy-key" "$OUT/deploy-key.pub"; ssh-keygen -q -t ed25519 -N "" -C "dms-deploy-$NAME" -f "$OUT/deploy-key"; echo "created $OUT/deploy-key (+ .pub)"; fi

# ---- 3. nginx site -----------------------------------------------------------------
say "3/5  nginx site"
conf="$OUT/nginx-dms.conf"
sed -e "s/^\(\s*server_name\s\).*;/\1${PUBLIC_HOST};/" "$NGINX_TEMPLATE" > "$conf"
if [ "$SEPARATE_NGINX" = 1 ]; then
  sed -i -e "s/server 127\.0\.0\.1:/server ${DOCKER_HOST_ADDR}:/" "$conf"
  echo "wrote $conf (upstreams -> $DOCKER_HOST_ADDR; install it on $NGINX_HOST)"
else
  echo "wrote $conf (nginx on the Docker server: upstreams stay 127.0.0.1)"
fi
grep -q "server_name ${PUBLIC_HOST};" "$conf" || die "could not set server_name in $conf"

# ---- 4. register in clients.yml ---------------------------------------------------------
say "4/5  infra/clients.yml"
if [ "$REGISTER" = 0 ]; then echo "skipped (--no-register)"
elif grep -qE "^\s*-\s*name:\s*${NAME}\s*$" "$CLIENTS_FILE"; then echo "$NAME is already listed"
else
  printf '\n  - name: %s\n    environment: %s\n    runner: %s\n    url: %s\n    mode: push\n    auto: false\n' "$NAME" "$ENV_NAME" "$RUNNER" "$PUBLIC_URL" >> "$CLIENTS_FILE"
  echo "added $NAME (mode push, auto false) - review and commit it"
fi

# ---- 5. server + GitHub ------------------------------------------------------------------
KNOWN="$OUT/known_hosts"
SSH_BASE=(-o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=yes -o ConnectTimeout=15)
if [ "$PROVISION" = 1 ]; then
  say "5/5  prepare the Docker server ($SSH_USER@$DOCKER_HOST_ADDR)"
  ssh-keyscan -T 4 -t ed25519 "$DOCKER_HOST_ADDR" 2>/dev/null > "$KNOWN" || true
  [ -s "$KNOWN" ] || die "could not read the host key of $DOCKER_HOST_ADDR (is it reachable on port 22?)"
  echo "Host key of $DOCKER_HOST_ADDR:"; ssh-keygen -lf "$KNOWN"
  echo "Compare it with the server's own:  ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"
  read -r -p "Does it match? Trust it? [y/N] " yn; [[ "$yn" =~ ^[yY]$ ]] || die "not trusted - stopping"
  echo "Installing the deploy key. ssh will ask for $SSH_USER's password (typed into ssh, not this script)."
  ssh "${SSH_BASE[@]}" "$SSH_USER@$DOCKER_HOST_ADDR" 'umask 077; mkdir -p ~/.ssh; cat >> ~/.ssh/authorized_keys' < "$OUT/deploy-key.pub"
  ssh -i "$OUT/deploy-key" -o IdentitiesOnly=yes -o BatchMode=yes "${SSH_BASE[@]}" "$SSH_USER@$DOCKER_HOST_ADDR" true \
    || die "key login does not work yet"
  echo "key login works."
  ssh -i "$OUT/deploy-key" -o IdentitiesOnly=yes -o BatchMode=yes "${SSH_BASE[@]}" "$SSH_USER@$DOCKER_HOST_ADDR" \
    "mkdir -p '$DEPLOY_PATH/infra/production'" || die "cannot create $DEPLOY_PATH - create it and give $SSH_USER write access (sudo mkdir; sudo chown), then re-run"
  scp -i "$OUT/deploy-key" -o IdentitiesOnly=yes -o BatchMode=yes "${SSH_BASE[@]}" "$OUT/env" "$SSH_USER@$DOCKER_HOST_ADDR:$DEPLOY_PATH/infra/production/.env"
  ssh -i "$OUT/deploy-key" -o IdentitiesOnly=yes -o BatchMode=yes "${SSH_BASE[@]}" "$SSH_USER@$DOCKER_HOST_ADDR" "chmod 600 '$DEPLOY_PATH/infra/production/.env'"
  echo ".env is in place on the server (mode 600)."
else
  say "5/5  server not touched (no --provision)"
  echo "Later: re-run with --provision, or copy $OUT/env to $DEPLOY_PATH/infra/production/.env (chmod 600) and install deploy-key.pub for $SSH_USER."
  ssh-keyscan -T 4 -t ed25519 "$DOCKER_HOST_ADDR" 2>/dev/null > "$KNOWN" || true
fi

[ -s "$KNOWN" ] || ssh-keyscan -T 4 -t ed25519 "$DOCKER_HOST_ADDR" 2>/dev/null > "$KNOWN" || true
if [ "$GITHUB" = 1 ]; then
  if command -v gh >/dev/null 2>&1; then
    say "GitHub environment $ENV_NAME"
    [ -s "$KNOWN" ] || die "no known_hosts line for $DOCKER_HOST_ADDR - run with --provision first"
    R=(); [ -n "$REPO" ] && R=(--repo "$REPO")
    repo_path="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
    if [ -n "$REVIEWERS" ]; then
      ids=""
      for login in ${REVIEWERS//,/ }; do
        id="$(gh api "users/$login" -q .id)" || die "GitHub user $login not found"
        ids="$ids{\"type\":\"User\",\"id\":$id},"
      done
      printf '{"reviewers":[%s]}' "${ids%,}" | gh api -X PUT "repos/$repo_path/environments/$ENV_NAME" --input - >/dev/null
      echo "approvers set: $REVIEWERS"
    else
      gh api -X PUT "repos/$repo_path/environments/$ENV_NAME" >/dev/null
    fi
    gh variable set DEPLOY_HOST --env "$ENV_NAME" --body "$DOCKER_HOST_ADDR" "${R[@]}"
    gh variable set DEPLOY_USER --env "$ENV_NAME" --body "$SSH_USER" "${R[@]}"
    gh variable set DEPLOY_PATH --env "$ENV_NAME" --body "$DEPLOY_PATH" "${R[@]}"
    gh variable set PUBLIC_URL  --env "$ENV_NAME" --body "$PUBLIC_URL" "${R[@]}"
    gh secret set DEPLOY_SSH_PRIVATE_KEY --env "$ENV_NAME" "${R[@]}" < "$OUT/deploy-key"
    gh secret set DEPLOY_SSH_KNOWN_HOSTS --env "$ENV_NAME" "${R[@]}" < "$KNOWN"
    echo "environment $ENV_NAME created with 4 variables and 2 secrets."
    if [ "$CLEANUP" = 1 ]; then
      rm -f "$OUT/env" "$OUT/deploy-key" "$OUT/deploy-key.pub"
      echo "removed the local .env and deploy key: the server and GitHub now hold the only copies."
    fi
  else
    echo "gh is not installed - enter the GitHub values by hand (below)."
  fi
fi

say "Done. What is left for you"
cat <<EOF
 1. GitHub -> Settings -> Environments -> $ENV_NAME:
      required reviewers (who approves a deploy here)$( [ "$GITHUB" = 1 ] && command -v gh >/dev/null 2>&1 || printf '\n      variables  DEPLOY_HOST=%s  DEPLOY_USER=%s  DEPLOY_PATH=%s  PUBLIC_URL=%s\n      secrets    DEPLOY_SSH_PRIVATE_KEY = contents of %s\n                 DEPLOY_SSH_KNOWN_HOSTS = contents of %s' "$DOCKER_HOST_ADDR" "$SSH_USER" "$DEPLOY_PATH" "$PUBLIC_URL" "$OUT/deploy-key" "$KNOWN" )
 2. A self-hosted runner that can SSH to $DOCKER_HOST_ADDR; put its label in the
    'runner' field of $NAME in infra/clients.yml (now: $RUNNER).
 3. nginx$( [ "$SEPARATE_NGINX" = 1 ] && echo " on $NGINX_HOST" ): install $conf as the site, then: nginx -t && systemctl reload nginx
$( [ "$SEPARATE_NGINX" = 1 ] && echo "    Firewall ports 3000, 8080 and 8081 on $DOCKER_HOST_ADDR to $NGINX_HOST only." )
 4. In $OUT/env review: KEYCLOAK_SUPERADMIN_USERNAME, SMTP_* (mail is NOT delivered
    while SMTP_HOST=mailpit), ALERT_EMAIL_TO, COMPOSE_PROFILES (bundled vs the
    client's own database/storage, docs/external-services.md).
 5. $PUBLIC_HOST must resolve from the runner (the post-deploy check requests it).
 6. Commit infra/clients.yml, then: Actions -> Deploy -> Run workflow, clients: $NAME
Keep $OUT private: it holds the .env secrets and the deploy private key.
EOF
