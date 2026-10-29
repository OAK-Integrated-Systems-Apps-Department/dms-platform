# Client: OAK iDocs

| | |
|---|---|
| Public name | `oakidocs.ois.local` |
| nginx server | `oakidocs.ois.local`, 192.168.3.50 |
| Docker services server | `oakidoc-svr.ois.local`, 192.168.0.51 |
| SSH user | `oak` |
| Client entry | `infra/clients.yml` -> `oakidocs` (environment `client-oakidocs`) |

## Quick path

```bash
bash infra/clients/new-client.sh --name oakidocs   --docker-host 192.168.0.51 --nginx-host 192.168.3.50   --public-url http://oakidocs.ois.local --ssh-user oak   --use-env infra/secrets/oakidocs.env --provision --github
```

`--use-env` keeps the `.env` already generated for this client (instead of making new
secrets). `--provision` asks you to confirm the server's host key, installs the deploy
key (you type the SSH password once into ssh), creates `/opt/dms` and copies the `.env`
there. `--github` sets the environment variables and secrets if the `gh` CLI is
installed; otherwise it prints them. Then do what the script lists at the end (approvers,
runner label, install the printed nginx file on 192.168.3.50, DNS). The detailed steps
below are what the script automates.

**No password or key is stored in this repository.** Deploys log in with an SSH key
held in the GitHub environment. The SSH password for this client was shared in chat
and should be treated as exposed: change it, and once key login works turn password
login off for `oak` on 192.168.0.51.

nginx is on a different server from Docker, which this stack does not assume by
default: its ports are published on `127.0.0.1`, and nginx proxies to `127.0.0.1`.
Steps 3 and 4 adjust both.

## 1. SSH key for deploys

On the machine that will run the deploy runner (any machine that can reach
192.168.0.51 over SSH):

```bash
ssh-keygen -t ed25519 -f oakidocs-deploy -C "dms-deploy" -N ""
ssh-copy-id -i oakidocs-deploy.pub oak@oakidoc-svr.ois.local     # asks for the password once
ssh-keyscan -t ed25519 oakidoc-svr.ois.local 192.168.0.51        # -> DEPLOY_SSH_KNOWN_HOSTS
```

Then delete the private key file from that machine once it is in GitHub.
Check the host key shown by `ssh-keyscan` against the server's own
(`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) before trusting it.

## 2. GitHub environment `client-oakidocs`

Settings -> Environments -> New environment. Add required reviewers.

Variables:

| Name | Value |
|---|---|
| `DEPLOY_HOST` | `192.168.0.51` |
| `DEPLOY_USER` | `oak` |
| `DEPLOY_PATH` | `/opt/dms` (the `oak` user must be able to write there) |
| `PUBLIC_URL` | `http://oakidocs.ois.local` (use `https://` once TLS is on nginx) |
| `BAO_ADDR` | only if secrets are read from OpenBao |

Secrets: `DEPLOY_SSH_PRIVATE_KEY` (contents of `oakidocs-deploy`) and
`DEPLOY_SSH_KNOWN_HOSTS` (the `ssh-keyscan` line).

Also install a self-hosted runner that can reach 192.168.0.51 and put its label in
the `runner` field of this client's entry in `infra/clients.yml`.

## 3. The server's `.env` (192.168.0.51)

Create `/opt/dms/infra/production/.env` from `.env.example` (`chmod 600`) and set:

```
COMPOSE_PROJECT_NAME=dms
PUBLIC_URL=http://oakidocs.ois.local
AUTH_COOKIE_SECURE=false              # true once nginx serves HTTPS
PROXY_BIND_ADDRESS=192.168.0.51       # nginx is on another server
MONITORING_CLIENT=oakidocs
MONITORING_ENVIRONMENT=production
GRAFANA_ROOT_URL=http://oakidocs.ois.local/grafana/
```

Replace every `CHANGE_ME` (generate with `openssl rand -hex 24`; no `/ @ : ? #`
in the database and RabbitMQ passwords) and set `INTERNAL_SERVICE_TOKEN` and
`OCR_JOB_TOKEN_SECRET` to two different values. Decide which services are bundled
and which are the client's own (`COMPOSE_PROFILES`; `docs/external-services.md`) and
set SMTP for real mail. Everything is bundled unless changed.

`PROXY_BIND_ADDRESS` publishes ports 3000, 8080 and 8081 on the LAN address. Firewall
those three to the nginx server (192.168.3.50) only.

## 4. nginx on 192.168.3.50

Install `infra/production/nginx/dms.conf` as the site, then change:

- `server_name` to `oakidocs.ois.local`;
- the three `server 127.0.0.1:...` lines in the `upstream` blocks to
  `server 192.168.0.51:3000;` (frontend), `:8080` (dms-core) and `:8081` (Keycloak).

Then `nginx -t && systemctl reload nginx`. Keep the rest of the file as it is: the
`/internal/` block is what stops the outside from reaching the OCR read endpoint.

## 5. First deploy

Actions -> Deploy -> Run workflow, `clients: oakidocs`. The post-deploy check requests
`PUBLIC_URL/login`, so `oakidocs.ois.local` must resolve from the runner.

## Not decided here

- HTTP or HTTPS for the public name (affects `PUBLIC_URL`, `AUTH_COOKIE_SECURE` and
  the Keycloak issuer; changing it later means re-checking Keycloak's redirect URLs).
- Whether this client uses the bundled database and storage or its own.
- The client name `oakidocs` and the environment name are my choice from the host
  name; rename them in `infra/clients.yml` and the GitHub environment if you prefer.
