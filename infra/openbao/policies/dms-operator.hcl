# Granted to human operators (auth/userpass). Manage the DMS secrets and
# take backups - but not change OpenBao's own auth/policy/audit setup,
# which needs a root token generated with the unseal keys.
path "secret/data/dms/*" {
  capabilities = ["create", "read", "update", "patch", "list"]
}
path "secret/metadata/dms/*" {
  capabilities = ["read", "list"]
}
path "sys/storage/raft/snapshot" {
  capabilities = ["read"]
}
path "auth/token/revoke-self" {
  capabilities = ["update"]
}
