# OpenBao server config for the DMS production host (oak-app).
# See docs/secrets-openbao.md for the full runbook.
#
# Single node with integrated (raft) storage: no external database, and
# `bao operator raft snapshot save` gives consistent backups.

ui = false

storage "raft" {
  path    = "/openbao/file"
  node_id = "oak-app"
}

# Plain HTTP is acceptable ONLY because the host port is bound to
# 127.0.0.1 (see docker-compose.yml): nothing off this machine can reach
# it, and the deploy runner runs on this machine. If OpenBao ever moves to
# its own host or is reached over the network, configure tls_cert_file /
# tls_key_file here first.
listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

# Every request and response (secret values HMAC'd, never plain) - who read
# what, when. OpenBao 2.x only accepts audit devices declared here, not via
# the API, so a compromised token can't switch auditing off or redirect it.
# Lives in the openbao_audit volume (/openbao/logs).
audit "file" "file" {
  options {
    file_path = "/openbao/logs/audit.log"
  }
}

api_addr     = "http://127.0.0.1:8200"
cluster_addr = "http://127.0.0.1:8201"

# Raft's own recommendation: mlock interacts badly with its memory-mapped
# files. Swap should instead be disabled/encrypted on the host.
disable_mlock = true

log_level = "info"
