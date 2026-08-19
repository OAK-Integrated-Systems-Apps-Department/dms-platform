# keycloak

Reusable, environment-agnostic Keycloak configuration-as-code - the parts
of setup that apply regardless of *where* Keycloak is running (local
Docker, a shared dev environment, or a real deployment). This is
deliberately separate from `infra/local/keycloak/`, which holds the
docker-compose-specific realm export used to auto-provision a throwaway
local instance.

## What's here

**`bootstrap.sh`** - grants the `dms-core-admin` client's service account
the `manage-users` role on `realm-management`. This is the one piece of
setup a Keycloak realm export genuinely cannot express declaratively: it
requires referencing another client's already-resolved internal id, which
doesn't exist yet during a single-pass realm import. Every other piece of
configuration `dms-core` needs (realm, roles, clients, the `tenant_id`
claim mapper) *is* fully expressed in the realm export at
`infra/local/keycloak/oak-dms-realm.json` and needs no separate script.

Previously this was a manual admin-console step (documented in
`backend/dms-core/README.md`); it's now run automatically as part of
`infra/local/docker-compose.yml` (see the `keycloak-bootstrap` service)
and by `scripts/setup-local-env.sh`. No manual Keycloak setup is required
for local development anymore.

## Running it against a non-local Keycloak

The script only needs `kcadm.sh` (bundled in every official Keycloak
image) and network access to the target instance:

```bash
KEYCLOAK_URL=https://keycloak.example.com \
KEYCLOAK_BOOTSTRAP_ADMIN_USER=admin \
KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=<real admin password> \
KEYCLOAK_REALM=oak-dms \
KEYCLOAK_ADMIN_CLIENT_ID=dms-core-admin \
  /opt/keycloak/bin/kcadm.sh --help >/dev/null && ./bootstrap.sh
```

(run from inside a Keycloak container/image, or anywhere `kcadm.sh` is on
`PATH` and pointed at the target server). It's idempotent - safe to run
against an environment where the role is already granted.

For a real (non-local) environment, the realm/clients/roles/claim-mapper
themselves should be provisioned via the same realm-export mechanism as
`infra/local/keycloak/oak-dms-realm.json` (Keycloak supports importing a
realm export at any time via the admin console or Admin REST API, not
just container startup), then this script run once afterward.
