# Granted to the GitHub Actions deploy job (auth/jwt role "dms-deploy"),
# for a 10-minute token per deploy. Read-only, one path, nothing else.
path "secret/data/dms/production/app" {
  capabilities = ["read"]
}
