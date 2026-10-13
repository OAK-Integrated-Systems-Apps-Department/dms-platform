#!/usr/bin/env bash
# Run SonarQube analysis for Oak DMS.
#
#   bash infra/sonarqube/scan.sh            # both projects
#   bash infra/sonarqube/scan.sh backend
#   bash infra/sonarqube/scan.sh frontend
#
# Needs SONAR_TOKEN in the environment (see infra/sonarqube/README.md for
# how to mint one). SONAR_HOST_URL defaults to the local server.
#
# The backend is analysed by the Maven scanner - it needs the compiled
# classes and the JaCoCo report, which only the build can produce. The
# frontend is analysed by the CLI scanner, run from a container so nobody
# has to install sonar-scanner to use this.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
TARGET="${1:-all}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "error: SONAR_TOKEN is not set." >&2
  echo "       Create one at $SONAR_HOST_URL under My Account > Security," >&2
  echo "       then: export SONAR_TOKEN=squ_..." >&2
  exit 1
fi

# Fail here with a sentence someone can act on, rather than 90 seconds later
# inside a scanner stack trace.
if ! curl -fsS "$SONAR_HOST_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; then
  echo "error: no SonarQube at $SONAR_HOST_URL, or it is still starting." >&2
  echo "       Start it:  docker compose -f infra/sonarqube/docker-compose.yml up -d" >&2
  echo "       Watch it:  docker logs -f sonarqube" >&2
  exit 1
fi

scan_backend() {
  echo "==> Backend: build, test, measure coverage"
  # verify, not test: the JaCoCo report goal is bound to verify, and the
  # scanner reads that report. Running `test` alone uploads no coverage.
  (cd "$REPO_ROOT/backend" && ./dms-core/mvnw -B -ntp -f pom.xml verify)

  echo "==> Backend: analyse"
  # host.url and token on the command line, not in the pom - see the
  # comment there. Maven reads them from the environment via -D.
  (cd "$REPO_ROOT/backend" && ./dms-core/mvnw -B -ntp -f pom.xml sonar:sonar \
    -Dsonar.host.url="$SONAR_HOST_URL" \
    -Dsonar.token="$SONAR_TOKEN")
}

scan_frontend() {
  echo "==> Frontend: analyse"
  # From the container's point of view the server is not on its own
  # localhost. host.docker.internal is how Docker Desktop reaches the host;
  # on plain Linux the --add-host line below makes that name resolve too.
  local url="$SONAR_HOST_URL"
  case "$url" in
    *localhost*|*127.0.0.1*)
      url="$(echo "$url" | sed -e 's/localhost/host.docker.internal/' -e 's/127\.0\.0\.1/host.docker.internal/')"
      ;;
  esac

  docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -e SONAR_HOST_URL="$url" \
    -e SONAR_TOKEN="$SONAR_TOKEN" \
    -v "$REPO_ROOT/frontend:/usr/src" \
    sonarsource/sonar-scanner-cli:11
}

case "$TARGET" in
  backend) scan_backend ;;
  frontend) scan_frontend ;;
  all)
    scan_backend
    scan_frontend
    ;;
  *)
    echo "usage: $0 [backend|frontend|all]" >&2
    exit 1
    ;;
esac

echo
echo "Done. Results: $SONAR_HOST_URL/projects"
