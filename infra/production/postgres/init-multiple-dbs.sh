#!/bin/sh
# Production counterpart of infra/local/init-multiple-dbs.sh: same databases
# and users, but the dms_core_user password comes from the environment
# (DMS_CORE_DB_PASSWORD, from the server-side .env) instead of being
# hardcoded in a committed file.
#
# Like every /docker-entrypoint-initdb.d script, this only runs when the
# postgres_data volume is EMPTY (first ever start). On a server that already
# has data it never runs - see docs/deployment.md "Existing data".
set -e

: "${DMS_CORE_DB_PASSWORD:?DMS_CORE_DB_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v dms_core_password="$DMS_CORE_DB_PASSWORD" <<-'EOSQL'
    CREATE USER dms_core_user WITH PASSWORD :'dms_core_password';
    CREATE DATABASE dms_core_db OWNER dms_core_user;
    GRANT ALL PRIVILEGES ON DATABASE dms_core_db TO dms_core_user;

    CREATE DATABASE keycloak_db OWNER postgres;
    GRANT ALL PRIVILEGES ON DATABASE keycloak_db TO postgres;
EOSQL
