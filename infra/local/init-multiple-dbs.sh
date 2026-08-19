#!/bin/bash
# Creates the separate databases/users this docker-compose stack needs
# (dms_core_db for the service, keycloak_db for Keycloak itself) on first
# container start, since the official postgres image only auto-creates
# one database out of the box.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER dms_core_user WITH PASSWORD 'dms_core_pass';
    CREATE DATABASE dms_core_db OWNER dms_core_user;
    GRANT ALL PRIVILEGES ON DATABASE dms_core_db TO dms_core_user;

    CREATE DATABASE keycloak_db;
EOSQL
