#!/usr/bin/env bash
# Builds an offline release bundle for a client whose server CI cannot reach
# (an on-prem or air-gapped install). Run it on a machine that can pull from the
# registry; hand the resulting file to the client's IT by whatever channel they
# allow.
#
#   ./make-release-bundle.sh <tag> [output-dir]
#
# <tag> is a release version (1.4.2, as published by pushing the git tag v1.4.2)
# or a full commit SHA. The bundle holds the three images (docker save), the
# compose file, deploy.sh and everything else deploy.yml uploads to a server, at
# the same paths - so on the server it unpacks straight into the install
# directory, and install-release.sh does the rest. No secrets are included:
# the client's .env and secrets stay on their server.
#
# Needs: docker (logged in to the registry if the images are private), tar, gzip.
# Env: REGISTRY_PREFIX (default ghcr.io/oak-integrated-systems-apps-department).
set -euo pipefail

cd "$(dirname "$0")/../.."   # repo root

tag="${1:-}"
out="${2:-.}"
[[ "$tag" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "usage: $0 <tag> [output-dir]" >&2; exit 2; }
prefix="${REGISTRY_PREFIX:-ghcr.io/oak-integrated-systems-apps-department}"
images=("$prefix/dms-core:$tag" "$prefix/dms-frontend:$tag" "$prefix/ocr-worker:$tag")

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
name="dms-release-$tag"
mkdir -p "$stage/$name"

echo "pulling images..."
for img in "${images[@]}"; do docker pull --quiet "$img" >/dev/null; echo "  $img"; done

echo "saving images (the OCR image is ~5.7 GB; this takes a while)..."
docker save "${images[@]}" | gzip > "$stage/$name/images.tar.gz"

# Same file set as .github/workflows/deploy.yml's "Upload deployment files".
tar -cf - \
  infra/production/docker-compose.yml \
  infra/production/deploy.sh \
  infra/production/backup.sh \
  infra/production/restore.sh \
  infra/production/install-release.sh \
  infra/production/.env.example \
  infra/production/secret-keys.txt \
  infra/production/secret-profiles.txt \
  infra/production/render-secrets.py \
  infra/production/postgres \
  infra/production/nginx \
  infra/openbao \
  infra/monitoring \
  infra/local/keycloak/oak-dms-realm.json \
  infra/keycloak/bootstrap.sh \
  | tar -xf - -C "$stage/$name"

printf '%s\n' "$tag" > "$stage/$name/RELEASE"
mkdir -p "$out"
tar -C "$stage" -czf "$out/$name.tar.gz" "$name"
( cd "$out" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256" )
echo "done: $out/$name.tar.gz (+ .sha256). On the server: see docs/multi-client-deployment.md."
