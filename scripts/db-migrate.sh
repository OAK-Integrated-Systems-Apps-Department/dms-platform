#!/usr/bin/env bash
# Thin wrapper around Flyway for dms-core, so migrations can be
# inspected/run outside of just "the app happens to start". Uses the
# same DB_URL/DB_USERNAME/DB_PASSWORD env vars as application.yml.
#
# Usage:
#   ./scripts/db-migrate.sh info      # show migration status
#   ./scripts/db-migrate.sh migrate   # apply pending migrations
#   ./scripts/db-migrate.sh validate  # validate applied vs. available migrations
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMAND="${1:-info}"

DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/dms_core_db}"
DB_USERNAME="${DB_USERNAME:-dms_core_user}"
DB_PASSWORD="${DB_PASSWORD:-dms_core_pass}"

cd "$REPO_ROOT/backend"
# Version pinned to match the flyway-core version Spring Boot 4.1's BOM
# manages, so this CLI tool and the app's own runtime Flyway agree on
# migration behavior. Re-check this against dms-core's actual resolved
# flyway-core version (mvn dependency:tree) if they ever drift.
FLYWAY_PLUGIN_VERSION="12.4.0"

mvn -q -B -pl dms-core -am \
    -Dflyway.url="$DB_URL" \
    -Dflyway.user="$DB_USERNAME" \
    -Dflyway.password="$DB_PASSWORD" \
    -Dflyway.locations=filesystem:dms-core/src/main/resources/db/migration \
    org.flywaydb:flyway-maven-plugin:"$FLYWAY_PLUGIN_VERSION":"$COMMAND"
