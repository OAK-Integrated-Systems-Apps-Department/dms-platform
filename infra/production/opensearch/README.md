# opensearch/ (production templates)

Nothing in this folder is applied by `docker-compose.yml`. It is used only with `docker-compose.opensearch.yml`.

- `opensearch.yml` - node settings (TLS, admin DN). A template.
- `certs/` - **not committed** (gitignored): `node.pem`, `node-key.pem`, `root-ca.pem`. Issue them from your own CA; the node
  certificate's subject alternative names must include `opensearch` (the compose service name dms-core connects to).
- `security/` - **not committed**: the security plugin's `internal_users.yml`, `roles.yml`, `roles_mapping.yml` with the
  low-privilege `dms-search` user (index patterns `dms-*`; rights: index, bulk, search, delete, aliases, create index; no cluster
  admin). Created once with `securityadmin.sh`. The exact files and the commands are in `docs/search-operations.md`.

The password of `dms-search` is the secret `OPENSEARCH_PASSWORD` (see `../secret-keys.txt`); never write it into a file here.
