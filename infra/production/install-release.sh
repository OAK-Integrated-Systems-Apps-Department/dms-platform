#!/usr/bin/env bash
# Installs an offline release bundle (see make-release-bundle.sh) on a server.
#
#   tar -xzf dms-release-1.4.2.tar.gz && cd dms-release-1.4.2
#   bash infra/production/install-release.sh
#
# Run from the unpacked bundle root. It loads the images into the local Docker,
# copies the deployment files into the install directory, then runs the same
# deploy.sh every other deploy uses - health checks, smoke test and automatic
# rollback included - with SKIP_PULL=1 because the images are already here.
#
# The install directory (default /opt/dms) must already hold this client's .env
# (from .env.example) and, with OpenBao, its rendered secrets - exactly as for
# a CI deploy; the bundle never contains secrets.
#
# Env: DEPLOY_PATH (default /opt/dms).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$root/images.tar.gz" ] && [ -f "$root/RELEASE" ] \
  || { echo "run this from an unpacked release bundle (images.tar.gz and RELEASE not found in $root)" >&2; exit 1; }

tag="$(tr -d '[:space:]' < "$root/RELEASE")"
dest="${DEPLOY_PATH:-/opt/dms}"
[ -f "$dest/infra/production/.env" ] \
  || { echo "$dest/infra/production/.env not found - create it from .env.example first" >&2; exit 1; }

echo "loading images for $tag..."
gunzip -c "$root/images.tar.gz" | docker load

echo "copying deployment files to $dest..."
mkdir -p "$dest"
( cd "$root" && tar -cf - infra ) | tar -xf - -C "$dest"

cd "$dest/infra/production"
SKIP_PULL=1 bash ./deploy.sh "$tag"
