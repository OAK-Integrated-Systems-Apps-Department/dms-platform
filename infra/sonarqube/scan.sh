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
  if [ "${SKIP_TESTS:-0}" = "1" ]; then
    # Quality analysis without coverage. Worth having when the test
    # database is not set up: bugs, vulnerabilities, smells and duplication
    # are all found by reading the code, and only the coverage figure is
    # lost. Sonar will show 0% - that is missing data, not a real zero.
    echo "==> Backend: compile only (SKIP_TESTS=1 - no coverage will be reported)"
    (cd "$REPO_ROOT/backend" && ./dms-core/mvnw -B -ntp -f pom.xml package -DskipTests)
  else
    echo "==> Backend: build, test, measure coverage"
    # The test suite talks to a REAL database - this project uses no
    # Testcontainers (docs/workflow-phase1-plan.md). It defaults to
    # localhost:5433/dms_dev as user postgres, which the local stack does
    # not provide: its Postgres has dms_core_db and a password from
    # infra/local/.env. So either set DB_* yourself or run with
    # SKIP_TESTS=1. Said here rather than after a ten-minute build that
    # ends in 31 "password authentication failed" errors.
    if [ -z "${DB_URL:-}" ] || [ -z "${DB_PASSWORD:-}" ]; then
      echo "error: the backend tests need a database, and DB_URL/DB_PASSWORD are not set." >&2
      echo >&2
      echo "       Analyse without coverage instead:" >&2
      echo "         SKIP_TESTS=1 bash infra/sonarqube/scan.sh backend" >&2
      echo >&2
      echo "       Or point them at a test database you have prepared:" >&2
      echo "         export DB_URL=jdbc:postgresql://localhost:5433/dms_test" >&2
      echo "         export DB_USERNAME=postgres" >&2
      echo "         export DB_PASSWORD=...        # POSTGRES_PASSWORD in infra/local/.env" >&2
      echo >&2
      echo "       See infra/sonarqube/README.md ('Backend coverage locally')." >&2
      exit 1
    fi
    # verify, not test: the JaCoCo report goal is bound to verify, and the
    # scanner reads that report. Running `test` alone uploads no coverage.
    (cd "$REPO_ROOT/backend" && ./dms-core/mvnw -B -ntp -f pom.xml verify)
  fi

  echo "==> Backend: analyse"
  # host.url and token on the command line, not in the pom - see the
  # comment there. Maven reads them from the environment via -D.
  #
  # The goal is fully qualified rather than the short `sonar:sonar`. Prefix
  # resolution only searches the configured pluginGroups, which do not
  # include org.sonarsource.scanner.maven - so the short form fails with
  # "No plugin found for prefix 'sonar'" even though the plugin IS declared
  # in backend/pom.xml. That declaration still supplies the version here.
  (cd "$REPO_ROOT/backend" && ./dms-core/mvnw -B -ntp -f pom.xml \
    org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
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

  # MSYS_NO_PATHCONV=1 is for Git Bash on Windows, and is ignored everywhere
  # else. Without it MSYS rewrites anything that looks like a Unix path
  # before Docker ever sees it, so `-v ...:/usr/src` mounts the source at a
  # literal `\Program Files\Git\usr\src` instead. The scanner then starts
  # fine, finds no sonar-project.properties, and fails with the thoroughly
  # misleading "You must define the following mandatory properties for
  # 'Unknown': sonar.projectKey".
  MSYS_NO_PATHCONV=1 docker run --rm \
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
