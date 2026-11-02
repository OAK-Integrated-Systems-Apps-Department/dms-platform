# Deployment runbook

Everything you need to deploy the DMS, in order:

- **Part A** - deploy the OAK server (`oakidocs`) for the first time.
- **Part B** - release new versions to OAK once it is live.
- **Part C** - add a new client while OAK is live, without touching OAK.
- **Part D** - day-to-day: backups, rollback, health checks.
- **Part E** - when something goes wrong.
- **Part F** - server and network basics: install Docker, install the runner, DNS, HTTPS.
- **Part G** - rotating secrets.
- **Part H** - monitoring across clients (what exists, and what does not yet).

Each step says **where** you run it (your computer, the Docker server, the nginx server, or
the GitHub website). Do the steps in order and stop if one fails.

> This runbook was written from the repository. The pipeline has not yet run end to end on
> real servers, so the first real deploy may show small problems. Fix them, and update this
> file so the next client benefits.

---

## 0. How it works

```
User's browser --> nginx server --> Docker server (the application)
                                        ^
GitHub ---- a robot logs in over SSH ---+   installs the new version
```

| Piece | What it is | What it needs |
|---|---|---|
| **Your computer** | where the code is and where you run the setup script | Git Bash, the repo |
| **GitHub** | stores the code, builds and scans the images, starts deploys | the server's address, user and SSH key (never the app's passwords) |
| **Docker server** | runs the application (and its database, storage, OCR, Keycloak, monitoring) | Docker, and the `.env` file with all the passwords |
| **nginx server** | the front door users' browsers talk to | a small config file that points to the Docker server |
| **Runner** | a small GitHub program on a machine that can reach the Docker server | network access to the Docker server |

Rules that never change:

1. **Each client is a separate installation:** its own servers, database, `.env`, secrets and
   GitHub environment. Nothing is shared between clients except the code and the images.
2. **The `.env` (all the passwords) lives only on that client's Docker server.** It is never
   put in GitHub or in the repository.
3. **No password is stored anywhere.** The SSH password is typed by you, into `ssh`, once.
4. **A client only gets a new version when you deploy to it by name.**

The list of clients is `infra/clients.yml`. A client entry has: `name`, `environment` (its
GitHub environment), `runner` (which runner reaches it), `url`, `mode` (`push` = GitHub
deploys to it) and `auto` (`true` = a merge to `main` deploys there automatically).

> A client marked `auto: true` in `infra/clients.yml` is deployed to by **every merge to
> `main`**. Today `oakidocs` is `auto: true`: every merge to `main` builds the images and
> deploys to it (a reviewer still has to approve, see A4). `oak-app` (GitHub environment
> `production`) is `auto: false` and is deployed by hand (Actions -> Deploy -> Run workflow,
> `clients: oak-app`). Change `auto:` on a client to change this.

---

## Part A - Deploy the OAK server (oakidocs) for the first time

### What you need

| Item | Value |
|---|---|
| Client name | `oakidocs` |
| Public URL | `http://oakidocs.ois.local` |
| nginx server | `oakidocs.ois.local`, 192.168.3.50 |
| Docker server | `oakidoc-svr.ois.local`, 192.168.0.51 |
| SSH user on the Docker server | `oak` (you know its password; never write it down in a file) |

Also check: the Docker server has **Docker with the Compose plugin**, at least **14 GB of
memory** and **30 GB of free disk** (the OCR service alone may use 8.5 GB of memory), and
the nginx server has nginx.

### A1. On the Docker server (192.168.0.51)

Log in with `ssh oak@192.168.0.51`, then:

```bash
docker --version                 # must print a version
docker compose version           # must print a version
# if either fails: install Docker first - Part F1
sudo mkdir -p /opt/dms
sudo chown oak /opt/dms
exit
```

### A2. On your computer - run the setup script

In Git Bash, **first go to the project folder** (the script path is relative to it; running
it from `~` gives `No such file or directory`):

```bash
cd ~/Documents/DMS/dms-platform
pwd                              # should end with .../dms-platform
ls infra/clients/new-client.sh   # should print the file name
bash infra/clients/new-client.sh --name oakidocs \
  --docker-host 192.168.0.51 --nginx-host 192.168.3.50 \
  --public-url http://oakidocs.ois.local --ssh-user oak \
  --use-env infra/secrets/oakidocs.env --provision
```

- It shows the server's **host key** and asks `Does it match? Trust it? [y/N]`. To be sure,
  run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the Docker server and compare.
  Then type `y`.
- It asks for the **`oak` password**. Type it there (it goes to `ssh`, not to the script).
- It installs a **deploy SSH key** on the Docker server, creates `/opt/dms`, and copies the
  **`.env`** to `/opt/dms/infra/production/.env` (mode 600).
- It writes the **nginx config** and your private copies into `~/.dms-clients/oakidocs/`:
  `env`, `deploy-key`, `deploy-key.pub`, `known_hosts`, `nginx-dms.conf`. Keep that folder
  private.

`--use-env infra/secrets/oakidocs.env` keeps the `.env` already generated for this client.
Without it the script generates a new one with fresh random secrets (what you will do for new
clients in Part C).

### A3. On the nginx server (192.168.3.50)

On your computer, print the config:

```bash
cat ~/.dms-clients/oakidocs/nginx-dms.conf
```

Copy the text to the nginx server and save it as `/etc/nginx/sites-available/dms`. Then, on the
nginx server:

```bash
sudo ln -s /etc/nginx/sites-available/dms /etc/nginx/sites-enabled/dms
sudo nginx -t && sudo systemctl reload nginx
```

(Adjust the paths if your nginx layout is different.) Do not edit the rest of the file: its
`/internal/` block is what stops the outside world from reaching the OCR read endpoint.

**Restricting the app ports (do this after the first deploy works):** ports 3000, 8080 and 8081
are published on 192.168.0.51 so nginx can reach them, and only nginx (192.168.3.50) should be
able to. **A normal host firewall rule (firewalld, ufw) does not do this**: Docker adds its own
rules, and traffic to a published port skips the host firewall. Use one of:

- a firewall **between the two networks** (the network team: allow 192.168.3.50 to
  192.168.0.51 on TCP 3000, 8080, 8081 and nothing else), or
- Docker's own `DOCKER-USER` chain on the Docker server (untested here - try it on a spare
  machine first):

  ```bash
  for port in 3000 8080 8081; do
    sudo iptables -I DOCKER-USER -p tcp -m conntrack --ctorigdstport $port --ctdir ORIGINAL          ! -s 192.168.3.50 -j DROP
  done
  ```

  These rules are lost on reboot unless you persist them (for example with the
  `iptables-services` package, or a small systemd unit that re-applies them after Docker starts).

Check from a machine that is **not** nginx: `curl -m 5 http://192.168.0.51:3000` must time out,
and from the nginx server it must answer once the app is deployed.

Also make sure the name `oakidocs.ois.local` resolves for the users, the runner **and the
Docker server itself** (Part F3). For HTTPS, see Part F4.

### A4. On the GitHub website - the client's environment

Repository -> **Settings -> Environments -> New environment**, named **`client-oakidocs`**.

Variables:

| Name | Value |
|---|---|
| `DEPLOY_HOST` | `192.168.0.51` |
| `DEPLOY_USER` | `oak` |
| `DEPLOY_PATH` | `/opt/dms` |
| `PUBLIC_URL` | `http://oakidocs.ois.local` |

Secrets - print each on your computer and paste it in:

```bash
cat ~/.dms-clients/oakidocs/deploy-key       # secret DEPLOY_SSH_PRIVATE_KEY
cat ~/.dms-clients/oakidocs/known_hosts      # secret DEPLOY_SSH_KNOWN_HOSTS
```

Add yourself (and whoever else may approve) as **required reviewers** on this environment, so
every deploy to this client needs a person to click Approve.

(If you install the `gh` CLI and run `gh auth login`, adding `--github --reviewers <login>` to
the command in A2 does this step for you. It is untested.)

### A5. On the GitHub website and your computer - the runner

The deploy job must run on a machine that can SSH to 192.168.0.51. How to install it: Part F2.

1. Repository -> **Settings -> Actions -> Runners -> New self-hosted runner**, and follow the
   instructions on a machine that can reach 192.168.0.51 over SSH. Give it a label, for example
   `oakidocs`.
2. In `infra/clients.yml`, set the `runner:` of `oakidocs` to that label.

### A6. On GitHub - one-time repository settings

- **Branch protection** on `main` (and `develop`): require the CI checks to pass before merging.
- Confirm **Actions** is enabled for the repository.

### A7. On your computer - get the code onto `main`

The images are built from `main`, so the repository changes must be there.

```bash
git status                       # review everything that changed
# commit on a branch, push, open a pull request, wait for CI to pass, then merge
```

The CI on the pull request runs backend, frontend and OCR tests, image scans and configuration
checks. Merging to `main` publishes images but does **not** deploy to `oakidocs`, because it is
`auto: false`.

### A8. On GitHub - the first deploy

**Actions -> Deploy -> Run workflow**, branch `main`, and in **clients** type `oakidocs`.

What happens:

1. CI runs again.
2. Three images are built and scanned, then published (**this first build is slow**: the OCR
   image is 5.7 GB and has no cache).
3. The deploy job waits for your **approval** (the reviewer clicks Approve).
4. It logs in to the Docker server, uploads the deploy files, pulls the images, starts the
   application, waits until dms-core and the frontend are healthy, and checks the login page
   through nginx.
5. If anything fails it rolls back by itself. A first install has no earlier version, so a
   failed first deploy simply stops; read the log, fix, and run it again.

### A9. Check that it works

- On your computer, open `http://oakidocs.ois.local`. You should see the login page.
- Log in as the Keycloak superadmin: the username is `KEYCLOAK_SUPERADMIN_USERNAME` and the
  password is `KEYCLOAK_SUPERADMIN_PASSWORD`, both in the server's `.env`:
  ```bash
  ssh oak@192.168.0.51 "grep '^KEYCLOAK_SUPERADMIN' /opt/dms/infra/production/.env"
  ```
  You are forced to change that password at the first login.
- On the Docker server:
  ```bash
  cd /opt/dms/infra/production
  ./deploy.sh --status             # deployed version and container health
  ```
  Expect `dms-core` and `frontend` healthy. The OCR worker can take a few minutes to warm up.

### A10. After the first deploy

1. **Change the `oak` SSH password** (it was shared in chat), then turn off password login for
   `oak` on the Docker server.
2. **Review the server's `.env`** (`/opt/dms/infra/production/.env`):
   - `KEYCLOAK_SUPERADMIN_USERNAME` - default `superadmin@gmail.com`; use a real address.
   - `SMTP_USERNAME` and `SMTP_PASSWORD` - the Gmail address and a Google app password (the
     other `SMTP_*` default to Gmail). Mail is **not delivered** without them; `deploy.sh`
     refuses a server whose `.env` still has `SMTP_HOST=mailpit`. Redeploy after setting them.
   - `ALERT_EMAIL_TO` - blank means monitoring alerts are not emailed.
3. **Take a first backup and copy it off the server:**
   ```bash
   cd /opt/dms/infra/production && ./backup.sh --label first
   ```
   And schedule backups (`crontab -e`, nightly at 02:00):
   ```
   0 2 * * * cd /opt/dms/infra/production && ./backup.sh --label nightly >> backups/cron.log 2>&1
   ```
   Also keep a safe copy of the server's `.env`: it is **not** part of the backup.
4. **Commit `infra/clients.yml`** if you have not (the `url` there lets the daily Fleet status
   check cover this client).
5. Remove the local secret copies once all of the above works: they are the same as what is on
   the server and in GitHub (`rm -r ~/.dms-clients/oakidocs/env ~/.dms-clients/oakidocs/deploy-key*`).

**Then the OAK system is live.**

---

## Part B - Release a new version to OAK (routine)

1. **Develop on a branch**, open a pull request to `develop` (or `main`). CI must pass.
2. **Merge to `main`.** This builds, scans and publishes the three images tagged with the commit
   (and deploys automatically only to clients marked `auto: true`, such as a staging client).
3. **Deploy to OAK:** Actions -> Deploy -> Run workflow, branch `main`, clients `oakidocs`.
   The approver clicks Approve.
4. The deploy first **backs up the databases** (the old version's data), then installs the new
   version, checks it, and rolls the application back by itself if it does not become healthy.
5. **Check:** open the site, and run `./deploy.sh --status` on the Docker server.

**If the new version is bad:**

- The application rolled back by itself: nothing to do except read the log.
- It deployed but is wrong: on the Docker server, `cd /opt/dms/infra/production && ./deploy.sh --rollback`
  (back to the previous version), or run Deploy with `image_tag` set to an older full commit id.
- The database changed and the old version does not work on it: restore the backup taken before
  the upgrade:
  ```bash
  ./restore.sh --list
  ./restore.sh backups/<time>-pre-<tag>      # type 'restore' to confirm
  ./deploy.sh <tag shown by restore.sh>
  ```

**Rule for developers:** every release must work on the previous release's database schema
(add first, remove later). That is what makes a rollback safe.

**Optional version tags:** pushing a tag like `v1.4.2` publishes the images under that version
without deploying. Use it for clients that pull releases themselves.

---

## Part C - A new client while OAK is live

OAK is **not touched** by any of this: the new client has its own servers, `.env`, secrets,
database and GitHub environment, and the script never overwrites another client's files.

### C0. Before you start - collect these

| Item | Example |
|---|---|
| Client name (lowercase letters, digits, `-`) | `acme` |
| Docker server address | `10.0.0.5` (an IPv4 address if nginx is on a different server) |
| nginx server address (only if separate) | `10.0.0.2` |
| Public URL | `https://dms.acme.example` (no path) |
| SSH user on the Docker server | `deploy` |
| Database: bundled, or their own (RDS, Azure, their server)? | bundled |
| Document storage: server disk, or their own S3 / Azure Blob? | server disk |
| Mail relay (SMTP) | `smtp.acme.example`, port, user, password |
| Who approves deploys to this client | GitHub logins |

If the client uses its **own** database, storage, Redis, RabbitMQ or mail relay, read
`docs/external-services.md` first; it lists exactly what the client must prepare.

### C1. On the new Docker server

Same as A1: Docker and the Compose plugin installed (Part F1), `/opt/dms` created and writable
by the SSH user, at least 14 GB memory and 30 GB free disk. Add the client's names to DNS (F3).

### C2. On your computer - run the setup script (no `--use-env`)

From the project folder (`cd ~/Documents/DMS/dms-platform`):

```bash
bash infra/clients/new-client.sh --name acme \
  --docker-host 10.0.0.5 --nginx-host 10.0.0.2 \
  --public-url https://dms.acme.example --ssh-user deploy \
  --provision
```

It generates a **new `.env` with fresh random secrets for this client**, a new deploy key, the
nginx config, and adds `acme` to `infra/clients.yml` (`auto: false`). It asks you to confirm the
host key and for the SSH password, like in A2.

If nginx runs on the **same** server as Docker, leave out `--nginx-host`.

### C3. Adjust the new client's `.env` if needed

Only when the client is not using the all-bundled default. On the new Docker server:

```bash
ssh deploy@10.0.0.5
nano /opt/dms/infra/production/.env
```

Set, as described in `docs/external-services.md`:

- `COMPOSE_PROFILES` - remove the names of bundled services the client replaces.
- The matching settings (`DB_HOST`, `S3_BUCKET`, `SMTP_HOST`, ...).
- `KEYCLOAK_SUPERADMIN_USERNAME` (a real address) and the `SMTP_*` mail settings.

The deploy refuses to start if a bundled service is switched off but its replacement is not set.

### C4. On the new client's nginx server

Same as A3, using `~/.dms-clients/acme/nginx-dms.conf`. For an HTTPS client, set up the TLS
certificate in nginx as the client does for their other sites, and keep `PUBLIC_URL` as
`https://...`.

### C5. On GitHub - the new environment

Same as A4 with the new values: environment **`client-acme`**, variables `DEPLOY_HOST`,
`DEPLOY_USER`, `DEPLOY_PATH`, `PUBLIC_URL`, secrets `DEPLOY_SSH_PRIVATE_KEY` and
`DEPLOY_SSH_KNOWN_HOSTS` from `~/.dms-clients/acme/`, and required reviewers.

### C6. The runner

The new server must be reachable by a self-hosted runner. If the same runner can reach it, reuse
it. Otherwise install another and give it a label. Set `runner:` for `acme` in `infra/clients.yml`.

### C7. Commit the new client entry

Review `infra/clients.yml` (the script added `acme` with its `url`), commit it on a branch, open a
pull request and merge. **Merging does not deploy to `acme`** (`auto: false`) and does not touch
OAK.

### C8. Deploy to the new client only

Actions -> Deploy -> Run workflow, branch `main`, clients **`acme`** - the new name **only**. The
reviewer approves. Then check it as in A9, and finish with A10 (password, `.env` review, first
backup, cron, remove local secret copies).

### C9. Cautions while OAK is live

- **Never type `all`** in the clients box unless you really want every client upgraded.
- OAK stays on its current version until you deploy it too. Keep clients within a few releases of
  each other: a client that skips many releases runs every database migration in between at once.
- Before the next upgrade of OAK, take a manual backup:
  `./backup.sh --label before-upgrade`.
- A good habit: add a **staging** client with `auto: true` in `infra/clients.yml`. Every merge to
  `main` then lands on staging first; deploy to real clients by hand once it looks healthy.
- If something in the OAK deploy needed fixing, fix it before onboarding the next client.

### C10. Updating many clients later

When a release has been healthy on staging and on one real client, deploy it to others with
`clients: acme,globex` (a list), or `all`. At most three servers are deployed at once, one failing
client does not stop the others, and each client needs its own approval.

---

## Part D - Day to day

| Task | Where | Command |
|---|---|---|
| Is every client up? | GitHub | Actions -> **Fleet status** (runs daily, or Run workflow) |
| Version and container health of one client | Docker server | `cd /opt/dms/infra/production && ./deploy.sh --status` |
| Back up now | Docker server | `./backup.sh --label manual` (add `--with-files` for the document files) |
| List backups | Docker server | `./restore.sh --list` |
| Restore a backup | Docker server | `./restore.sh backups/<folder>` then `./deploy.sh <tag>` |
| Roll back the application | Docker server | `./deploy.sh --rollback` |
| See the logs of a service | Docker server | `docker compose logs --tail=100 dms-core` |
| Add a client | your computer | Part C |

Backups are copied nowhere by themselves: copy them off the server. A restore replaces the
current data, so practise it on a spare server first. More in `docs/operations.md`.

---

## Part E - When something goes wrong

| Where | What you see | Likely cause and fix |
|---|---|---|
| Step A2 | cannot create `/opt/dms` | step A1 not done; give the SSH user write access to `/opt/dms` |
| Step A2 | host key does not match | stop; you may be connecting to the wrong machine |
| Step A2 | key login does not work | the key was not installed; re-run with `--provision --force` |
| Deploy, plan step | `unknown client` | the name is not in `infra/clients.yml` |
| Deploy, before anything starts | `secrets not available` or `CHANGE_ME` | the server's `.env` is incomplete |
| Deploy, before anything starts | `a bundled service is switched off ... replacement is not set` | finish the external-service settings in `.env` |
| Deploy, before anything starts | `the pre-upgrade backup failed` | fix the backup (disk space? database running?), or `SKIP_BACKUP=1` only if you accept the risk |
| Deploy, publish | image scan fails | a fixable CRITICAL vulnerability: update the dependency, or record an accepted exception in `.trivyignore` with a reason |
| Deploy, pull | `denied` pulling an image | the package is not linked to the repository, or the token lacks access: check the package settings in GitHub |
| Deploy, last check | `/login` does not return 200 (`-> 000`) | nginx not set up, or the name does not resolve from the runner: see E1; for a public IP see F5 |
| Deploy, health wait | `timed out waiting for dms-core` | look at the log lines it prints (database, Keycloak, storage settings) |
| After login | emails never arrive | `SMTP_USERNAME`/`SMTP_PASSWORD` missing, or the password is not a Google *app password* |
| After login | OCR does not run | check `docker compose logs ocr-worker`; give it time to warm up; check the server has enough memory |
| A deployed version is wrong | | Part B, "If the new version is bad" |

Still stuck: copy the failing step's log (without passwords) and the output of
`./deploy.sh --status`, and ask for help with those.

### E1. Problems met in the first real deploy (oakidocs), with fixes

In the order they happened. Check the prompt first: the Docker server (`oak@oakidoc-svr`) and the
nginx server (`oak@oakidocs`) are different machines, and several "missing file" problems were
only a command typed on the wrong one.

**Before the deploy starts**

| What you see | Cause | Fix |
|---|---|---|
| Job stays at "Waiting for a runner to pick up this job" (`Requested labels: oakidocs`) | The runner was named `oakidocs` but its **labels** were only `self-hosted, Linux, X64` | Settings → Actions → Runners → the runner → Labels → add `oakidocs`; cancel the waiting run and start a new one. Also check `sudo ./svc.sh status` and that the runner belongs to this repository (or an organisation group that allows it) |
| `missing configuration: vars.DEPLOY_HOST ... secrets.DEPLOY_SSH_...` | Values not in the environment `client-oakidocs`, saved under the wrong tab (Variables vs Secrets), or never generated | Variables: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH`, `PUBLIC_URL`. Secrets: `DEPLOY_SSH_PRIVATE_KEY`, `DEPLOY_SSH_KNOWN_HOSTS`, which are the files `~/.dms-clients/<name>/deploy-key` and `known_hosts` made by step A2 on the PC that ran it (Git Bash `~` is `C:\Users\<you>`). If the folder is missing, run A2 again |
| `.env not found in /home/oak/\opt\dms/infra/production` (backslashes) | `DEPLOY_PATH` was saved as `\opt\dms`, so it became a relative path and a stray folder `~/\opt\dms` was created | Set `DEPLOY_PATH` to exactly `/opt/dms`; delete the stray folder with `rm -rf ~/'\opt\dms'` (quoted). In Windows Git Bash prefix `scp`/`ssh` commands that carry a `/path` with `MSYS_NO_PATHCONV=1` |
| `still CHANGE_ME: #KEYCLOAK_DB_PASSWORD #S3_ACCESS_KEY ...` (names start with `#`) | Bug in `deploy.sh`: the preflight matched commented-out example lines of `.env.example` | Fixed in the code: the pattern now needs a real assignment. Until that fix is on `main` (the deploy copies `deploy.sh` from the deployed commit), delete those commented lines from the server's `.env`: `sed -i -E '/^#(NAME1\|NAME2)=CHANGE_ME$/d' .env` |

**During the deploy**

| What you see | Cause | Fix |
|---|---|---|
| All containers healthy, but `frontend via nginx: GET http://<name>/login -> 000`, then `FAILED ... did not become healthy` and `no previous tag to roll back to` | The smoke test goes through nginx and the Docker server could not reach it (`curl` said `No route to host` for `<nginx>:80`). The "no previous tag" line is normal on a first deploy | From the Docker server: `ping <nginx>` and `curl -sv http://<nginx>/login -H 'Host: <name>'`. On the nginx server: `ss -ltnp \| grep nginx`; open the port (`firewall-cmd --permanent --add-service=http && firewall-cmd --reload`); with SELinux Enforcing run `setsebool -P httpd_can_network_connect 1`. Between subnets ask the network admin to allow Docker server → nginx on 80 (or the port used) and nginx → Docker server on 3000, 8080, 8081. Then re-run the deploy; the containers are already up so it is quick |
| `relation "processing_jobs" does not exist` in the OCR worker log | Harmless start-up race: the OCR reaper ran before `dms-core` finished its migrations | Nothing. Look for the first `[deploy]` line above the log dump to find the real failure |
| Grafana `unhealthy` or `starting` | Optional monitoring service; the deploy only warns | Ignore for the release; later `docker logs --tail 30 oakdms-grafana` |

**Logging in**

| What you see | Cause | Fix |
|---|---|---|
| Where is the superadmin password? | Generated at setup | User `superadmin@gmail.com`; the password is the line `KEYCLOAK_SUPERADMIN_PASSWORD=` in `infra/secrets/<name>.env` (or `~/.dms-clients/<name>/env`), same as the server's `.env`. Print only that line with `grep`, from the right folder (`infra/secrets/...` is relative to the project). It is set only if the account has no password yet, as a *temporary* one: Keycloak asks for a new one at first login |
| "Timeout when waiting for 3rd party check iframe message" | The address in the browser differs from `PUBLIC_URL`, so Keycloak's iframe cannot load (for example opened by IP address, or from outside where the private name does not resolve); blocked third-party cookies can also cause it | Open exactly `PUBLIC_URL`. Check `<PUBLIC_URL>/realms/oak-dms/protocol/openid-connect/3p-cookies/step1.html` loads; look at the first failing request in the browser's Network tab; try a private window |
| Superadmin login rejected | Password changed earlier, or never applied | Reset it (below) |

Reset the superadmin password (the Keycloak console is not exposed by nginx). On the Docker
server; secrets travel as environment variables so they are neither shown nor kept in the shell
history. `kcadm.sh` cannot read a password from a pipe ("Console is not active"), and
`kcadm.sh config truncate` does not exist in this version:

```bash
cd /opt/dms/infra/production
pw="$(grep '^KEYCLOAK_ADMIN_PASSWORD=' .env | tail -n1 | cut -d= -f2- | tr -d '\r')"
read -rs -p "New superadmin password: " NEWPW; echo
docker exec -e PW="$pw" -e NEWPW="$NEWPW" oakdms-keycloak sh -c '
  /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password "$PW" &&
  /opt/keycloak/bin/kcadm.sh set-password -r oak-dms --username superadmin@gmail.com --new-password "$NEWPW"'
unset pw NEWPW
docker exec oakdms-keycloak sh -c 'rm -f "$HOME/.keycloak/kcadm.config"'
```

The browser alternative: `ssh -L 8081:<docker-server>:8081 oak@<docker-server>`, open
`http://localhost:8081`, sign in as `admin` with `KEYCLOAK_ADMIN_PASSWORD`, realm **oak-dms** →
Users → the user → Credentials → Reset password (Temporary off).

**CI problems fixed along the way**

| Failure | Cause | Fix |
|---|---|---|
| `WatermarkServiceIT` flaky (two jobs for one key) | the worker failed fast and released the idempotency key | the job queue is mocked in that test |
| `ImpersonationIT` HTTP 500 on admin password reset | no SMTP in CI; the mail failure propagated | `JavaMailSender` mocked in the test |
| Watermark audit counted 0 | `WATERMARK_COMPLETED` is written just after the status commit | tests poll for it (`auditCountAfterWorker`) |
| Trivy CRITICALs in the images | old Tomcat, Netty, Spring, brotli-libs, Tika, Next.js | newer versions pinned in `pom.xml`; `apk upgrade` in the Dockerfile; Next 16.4.3 |
| "OCR worker unit tests" shown as skipped | by design: they run for pull requests into `develop`; PRs into `main` use `ocr-accuracy-gate.yml` | none |

**After the first deploy**

1. Change the SSH password of the deploy user and then disable password login (deploys use the key).
2. Restrict 3000, 8080 and 8081 on the Docker server to the nginx server (see A3: Docker's
   published ports bypass a normal host firewall).
3. Remove duplicate lines added to `/etc/hosts` while testing.
4. First backup: `./backup.sh --label first`.
5. Branch protection on `main`; required reviewers on the client's environment.
6. Rotate any secret that was ever printed in a chat or log.

---

## Part F - Server and network basics

### F1. Install Docker on a server (Ubuntu or Debian)

On the Docker server, as a user with `sudo`. These are Docker's own official steps for Ubuntu;
for **Debian** replace `ubuntu` with `debian` in the two URLs. For other systems (RHEL, Rocky,
SUSE) follow https://docs.docker.com/engine/install/.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker oak        # let the deploy user run docker; replace oak with the SSH user
sudo systemctl enable --now docker
```

Log out and back in (so the group applies), then check:

```bash
docker --version
docker compose version
docker run --rm hello-world         # needs internet access to Docker Hub
curl -sI https://ghcr.io | head -1  # the application images come from here
```

The server needs **outbound internet access** to `ghcr.io` and the public image registries
(Docker Hub, `quay.io`, `gcr.io`) at deploy time. Servers with no internet are not supported by
the normal flow.

Also check the basics: `free -h` (about 14 GB or more), `df -h /` (30 GB or more free), and
that the server's clock is right (`timedatectl`), because TLS and logins depend on it.

### F2. Install the self-hosted runner

The runner is a small GitHub program that runs the deploy jobs. It must be on a machine that can
**SSH to the Docker server** and, for the final health check, **reach the public URL**. A small
separate Linux machine is best. It can be the nginx server if you accept that, but it should not
be a machine that anyone untrusted can use: a runner executes whatever the workflows tell it to.
Use runners with **private repositories only**.

Needs on the runner machine: `ssh`, `curl`, `tar`, `python3`, `git`, and a normal (non-root)
user to run it.

1. In GitHub: repository -> **Settings -> Actions -> Runners -> New runner -> New self-hosted
   runner**, choose **Linux**. GitHub then shows the exact download and `config.sh` commands with
   the current version and a short-lived registration token. Use those, not an old copy.
2. Run them on the runner machine. They look like this (use GitHub's values for the parts in
   angle brackets):

   ```bash
   mkdir ~/actions-runner && cd ~/actions-runner
   curl -o runner.tar.gz -L <the download URL GitHub shows>
   tar xzf runner.tar.gz
   ./config.sh --url https://github.com/<owner>/<repo> --token <token from the page> \
               --labels oakidocs --unattended
   ```

   `--labels oakidocs` gives the runner a label. A runner that serves several clients can have a
   shared label such as `lan-runner`.
3. Run it as a service so it survives reboots:

   ```bash
   sudo ./svc.sh install
   sudo ./svc.sh start
   sudo ./svc.sh status
   ```
4. The runner should appear as **Idle** in Settings -> Actions -> Runners.
5. In `infra/clients.yml`, set `runner:` of the client to the label you chose (`oakidocs`), and
   commit it.
6. Check it can reach the server: on the runner machine, log in as the runner's user and run
   `ssh oak@192.168.0.51 true` once, to confirm the network path (the deploy itself uses the key,
   not your password).

If a runner goes offline, deploys to the clients that use its label wait until it is back.

### F3. Names (DNS or hosts entries)

Which name points where:

| Name | Points to | Used by |
|---|---|---|
| `oakidocs.ois.local` (the public name) | the **nginx** server, 192.168.3.50 | users' browsers, the runner, **and the Docker server** |
| `oakidoc-svr.ois.local` | the Docker server, 192.168.0.51 | optional; deploys use the IP |

The Docker server and the runner both need the public name because the deploy's last check
requests `PUBLIC_URL/login` from them, and the daily Fleet status check does the same from the
runner.

**Best:** an `A` record in your internal DNS: `oakidocs.ois.local  ->  192.168.3.50`.

**Or a hosts entry**, on each machine that needs it:

```bash
# Linux (runner, Docker server):
echo "192.168.3.50  oakidocs.ois.local" | sudo tee -a /etc/hosts
```

On a Windows PC, open Notepad **as Administrator**, edit
`C:\Windows\System32\drivers\etc\hosts`, and add the line `192.168.3.50  oakidocs.ois.local`.

Test from each machine:

```bash
getent hosts oakidocs.ois.local            # Windows: nslookup oakidocs.ois.local
curl -sI http://oakidocs.ois.local/login   # expect HTTP/1.1 200 (after the first deploy)
```

Tip: some systems treat names ending in `.local` as multicast DNS and can be slow or fail to
resolve them. If you see that, use a name under a domain you control for new clients.

### F4. HTTPS on nginx

Plain HTTP is fine on a private LAN. Use HTTPS when users connect over the internet, when the
client's rules require it, or when you want logins protected on the network. Keycloak only allows
plain HTTP from private addresses, so exposing the site beyond the LAN **requires** HTTPS.

1. **Get a certificate** for the public name:
   - a public name: Let's Encrypt (`sudo apt install certbot`, then `certbot certonly --nginx -d
     <name>`, which also renews automatically);
   - an internal name (like `.local`): a certificate from the company's own certificate
     authority. A self-signed one works but every browser and machine will warn unless it trusts
     it.
2. **Put the certificate and key on the nginx server** (for example `/etc/nginx/ssl/oakidocs.crt`
   and `.key`; make the key readable only by root: `sudo chmod 600 ...key`).
3. **Edit the site file** (`/etc/nginx/sites-available/dms`). In the existing `server { ... }`
   block, replace the two `listen 80 ...` lines with the following, and add the certificate lines
   under `server_name`. Keep every `location` block unchanged. Then add the small redirect
   block **above** it:

   ```nginx
   server {                         # new: send plain HTTP to HTTPS
       listen 80;
       listen [::]:80;
       server_name oakidocs.ois.local;
       return 301 https://$host$request_uri;
   }

   server {                         # the existing block, with these lines changed
       listen 443 ssl;
       listen [::]:443 ssl;
       server_name oakidocs.ois.local;
       ssl_certificate     /etc/nginx/ssl/oakidocs.crt;
       ssl_certificate_key /etc/nginx/ssl/oakidocs.key;
       ssl_protocols TLSv1.2 TLSv1.3;
       # ... all the existing settings and location blocks stay as they are ...
   }
   ```

   The file already sends `X-Forwarded-Proto`, which Keycloak and the application need to know the
   site is HTTPS. Then `sudo nginx -t && sudo systemctl reload nginx`.
4. **Change the settings to HTTPS.**
   - On the Docker server, in `/opt/dms/infra/production/.env`:
     `PUBLIC_URL=https://oakidocs.ois.local`, `AUTH_COOKIE_SECURE=true`,
     `GRAFANA_ROOT_URL=https://oakidocs.ois.local/monitoring/panels/`.
   - In the GitHub environment `client-oakidocs`, change the variable `PUBLIC_URL` to the
     `https://` address.
   - In `infra/clients.yml`, change the client's `url:` to the `https://` address and commit it.
5. **Make the runner and the Docker server trust the certificate.** The deploy and the Fleet
   status check use `curl`, which refuses a certificate it does not trust. For an internal
   authority, install its root certificate on both machines (on Ubuntu: copy it to
   `/usr/local/share/ca-certificates/` as `.crt`, then `sudo update-ca-certificates`).
6. **Redeploy** (Part B, step 3). The deploy re-applies the Keycloak addresses from `PUBLIC_URL`
   on every run, so logins keep working over the new address.
7. **Test:** open `https://oakidocs.ois.local`, log in, and run `curl -I https://oakidocs.ois.local/login`
   from the runner. People will be logged out once when you switch, so do it outside working hours.

### F5. Reaching it from the internet through a public IP

A public IP belongs to the router. Only a **port forward** on the router sends
`<public-ip>:<port>` to `<nginx-server>:<port>`; changing nginx's port alone does nothing.
Without access to the router, a Cloudflare Tunnel (needs a domain managed in Cloudflare) or
Tailscale (private, for staff only) need no forwarding.

1. **Prove the forward works:** open `http://<public-ip>:<port>` on a phone on mobile data and
   watch the request arrive on the nginx server: `sudo tail -f /var/log/nginx/access.log | grep -v '^<docker-server-ip>'`.
2. **Make nginx serve the app on that port.** A default `server` block in `nginx.conf` may be
   answering it instead (oakidocs: port 5173 showed the stock page). In `conf.d/dms.conf`:
   ```
   listen 80 default_server;
   listen [::]:80 default_server;
   listen 5173 default_server;
   listen [::]:5173 default_server;
   server_name oakidocs.ois.local 124.43.20.218;
   ```
   then `sudo nginx -t && sudo systemctl reload nginx`. With SELinux or firewalld, a port nginx
   is not already listening on needs `semanage port -a -t http_port_t -p tcp <port>` and
   `firewall-cmd --permanent --add-port=<port>/tcp`.
3. **The Docker server usually cannot open the public IP from inside** (no hairpin NAT; `curl`
   prints `000`), yet the deploy's smoke test and the workflow's "Verify from the network" step
   call `PUBLIC_URL`. Set `SMOKE_URL` to the address the server itself can use, for example
   `http://oakidocs.ois.local`: in the server's `.env` (read by `deploy.sh`) and as a variable in the
   client's GitHub environment (read by the workflow). Unset, nothing changes. The Keycloak
   issuer check still compares against `PUBLIC_URL`.
4. **Switch `PUBLIC_URL`** to `http://<public-ip>:<port>` in the GitHub environment and in the
   server's `.env`, then redeploy. Keycloak's address, the token issuer, CORS and the redirect
   URIs all follow `PUBLIC_URL`. After this the old private name can no longer sign in:
   **everyone uses the one address.**
5. The nginx template sends `Host $host`, which drops the port. Keycloak ignores it (its
   `KC_HOSTNAME` is fixed), but if redirects lose the port, look there.
6. Plain HTTP over the internet sends passwords unencrypted. Use it for testing only, and put a
   domain with HTTPS in front (F4) before real use. Never forward 3000, 8080, 8081 (Keycloak
   admin), Grafana or RabbitMQ.

---

## Part G - Rotating secrets

Rotate when someone who knew a secret leaves, when a secret may have leaked (a password shared
in chat counts), and on a regular schedule your client requires.

**These procedures are worked out from the configuration, not rehearsed. Try each one first on a
staging install.** Rules for all of them: take a backup first (`./backup.sh --label before-rotation`),
change **one secret at a time**, keep the old value until the new one is proven, and never paste
a secret into chat or a ticket. On the Docker server, work in `/opt/dms/infra/production`. If this
client keeps its secrets in OpenBao, change the value there instead of in `.env`.

Generate a new value with `openssl rand -hex 24` (hex only; some secrets are used inside URLs).

| Secret | How | Notes |
|---|---|---|
| **Deploy SSH key** (GitHub secret) | Make a new key on your computer (`ssh-keygen -t ed25519 -f newkey -N ""`), add `newkey.pub` to `~oak/.ssh/authorized_keys` on the Docker server, put the private key into the GitHub secret `DEPLOY_SSH_PRIVATE_KEY`, run a deploy to prove it works, then delete the **old** line from `authorized_keys`. | Never remove the old key before the new one is proven. |
| **`INTERNAL_SERVICE_TOKEN`**, **`OCR_JOB_TOKEN_SECRET`** | Put the new value (or values) in `.env`, then `docker compose up -d dms-core ocr-worker`. | Jobs in flight may fail once and be retried. |
| **`DMS_CORE_DB_PASSWORD`** (bundled database) | `docker compose exec -T postgres psql -U postgres -c "ALTER USER dms_core_user PASSWORD '<new>'"`, put `<new>` in `.env`, then `docker compose up -d --no-deps dms-core ocr-worker`. | `.env` alone does not change the password inside an existing database. The next deploy also recreates the database container briefly (its settings changed); data stays. For a client's own database, change it in their database first. |
| **`RABBITMQ_DEFAULT_PASS`** (bundled) | `docker compose exec rabbitmq rabbitmqctl change_password dms '<new>'`, put it in `.env`, `docker compose up -d --no-deps dms-core ocr-worker`. | Same as the database: `.env` alone only matters when the broker is first created. |
| **`REDIS_PASSWORD`** | Edit `.env`, `docker compose up -d redis dms-core redis-exporter`. | Redis restarts, so cached values are rebuilt. |
| **Keycloak admin password, `KEYCLOAK_ADMIN_CLIENT_SECRET`** | Change it in the Keycloak admin console first (for the client secret: regenerate the secret of client `dms-core-admin`), then put the same value in `.env`, then redeploy. | The deploy logs in to Keycloak with these values; they must match what Keycloak holds. |
| **`SMTP_PASSWORD`** | Change at the mail provider, update `.env`, redeploy. | |
| **`S3_ACCESS_KEY`/`S3_SECRET_KEY`, `AZURE_STORAGE_ACCOUNT_KEY`** | Create a second key at the provider, put it in `.env`, redeploy, check an upload and a download, then revoke the old key. | |
| **`GRAFANA_ADMIN_PASSWORD`**, **`POSTGRES_METRICS_PASSWORD`** | Change them in Grafana / the database first, then in `.env`, then `docker compose up -d grafana postgres-exporter`. | |
| **`AUDIT_CORRELATION_SECRET`**, **`IMPERSONATION_SECRET`** | **Do not rotate without a developer's review.** | Changing them may invalidate existing audit correlations or active impersonation sessions; the effect has not been checked. |

After any rotation: log in to the application, upload and open a document, and run
`./deploy.sh --status`. Then update the copy of `.env` you keep safe.

No tool rotates secrets for you yet, and there is no central store of secrets for the fleet.

---

## Part H - Monitoring across clients

**What exists now**

- **Per client:** every installation runs its own monitoring (Prometheus, Grafana, Alertmanager).
  The dashboards are shown inside the application, on the **System Monitoring** page of an
  administrator. Alerts are also emailed to `ALERT_EMAIL_TO` in the client's `.env` through the
  SMTP settings, so set both on every client.
- **All clients at a glance (is it up?):** the **Fleet status** workflow. It runs daily, or on
  demand (Actions -> Fleet status -> Run workflow), and checks each client's login page, the API
  through nginx and Keycloak. A failing client fails the run, and GitHub notifies the people watching the
  repository. Every client must have a `url:` in `infra/clients.yml` to be included.
- **One client in detail:** on its Docker server, `./deploy.sh --status` (version and container
  health) and `docker stats` (memory).

**A simple way to get one inbox for all clients:** point every client's `ALERT_EMAIL_TO` at the
same shared mailbox. Each install's alerts carry the client's name (`MONITORING_CLIENT` in its
`.env`); send yourself one test alert and check the name shows in the email before relying on it.

**What does not exist yet** (so there are no steps for it): a single dashboard with the metrics
of all clients together. That needs a central Prometheus that every install sends its metrics to;
the `MONITORING_CLIENT` label is already in place for it. Until it is built, use the Fleet status
workflow and the per-client alert emails above, and give one person the job of looking at both.

---

## Appendix - where things are

| What | Where |
|---|---|
| List of clients | `infra/clients.yml` |
| Setup script | `infra/clients/new-client.sh` |
| The application's configuration | `infra/production/docker-compose.yml` (the same for every client) |
| A client's own settings and secrets | the `.env` on its Docker server (`/opt/dms/infra/production/.env`) |
| The `.env` template | `infra/production/.env.example` |
| Deploy, backup, restore scripts | `infra/production/deploy.sh`, `backup.sh`, `restore.sh` |
| The deploy pipeline | `.github/workflows/deploy.yml` |
| Daily health check | `.github/workflows/fleet-status.yml` |
| Per-client notes | `docs/clients/` |
| Own database / storage / mail | `docs/external-services.md` |
| Backups, upgrades, secrets | `docs/operations.md` |
| Many clients in general | `docs/multi-client-deployment.md` |
