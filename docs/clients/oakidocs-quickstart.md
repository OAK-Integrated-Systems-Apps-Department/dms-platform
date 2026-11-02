# OAK iDocs: setup steps

A checklist for putting the DMS on the oakidocs servers. Each step says where you run it.
Do them in order, and stop if one fails.

| Server | Address | Role |
|---|---|---|
| nginx | `oakidocs.ois.local` / 192.168.3.50 | the front door users' browsers talk to |
| Docker services | `oakidoc-svr.ois.local` / 192.168.0.51 | runs the application |
| SSH user | `oak` | used to log in to the Docker server |

```
User's browser --> nginx 192.168.3.50 --> app on Docker server 192.168.0.51
                                                ^
GitHub --- a robot logs in with an SSH key -----+   (installs the new version)
```

**Where things live**

- The server's `.env` (all the application's passwords) lives **only on the Docker server**.
  The script in step 2 copies it there. It is never put in GitHub.
- GitHub only needs the keys to reach the Docker server: its address, the user name and an
  SSH key.
- nginx only needs a small config file saying where the app is.

No password is written in this file or anywhere in the repository. The SSH password is typed
by you, into `ssh`, in step 2.

---

## Step 1. On the Docker server (192.168.0.51)

Log in with `ssh oak@192.168.0.51`, then run:

```bash
docker --version          # must print a version; if it does not, install Docker first
sudo mkdir -p /opt/dms
sudo chown oak /opt/dms
exit
```

## Step 2. On your own computer

Open Git Bash and go to the project folder first (`cd ~/Documents/DMS/dms-platform`), then run this one command. It asks for the `oak`
password: type it there.

```bash
bash infra/clients/new-client.sh --name oakidocs \
  --docker-host 192.168.0.51 --nginx-host 192.168.3.50 \
  --public-url http://oakidocs.ois.local --ssh-user oak \
  --use-env infra/secrets/oakidocs.env --provision
```

It shows the server's host key and asks `Does it match? Trust it? [y/N]`: type `y`. Then it:

1. installs a deploy SSH key on the Docker server,
2. creates `/opt/dms` if needed and copies the `.env` to `/opt/dms/infra/production/.env`
   (mode 600),
3. writes the nginx config file for you.

Everything it makes is in `~/.dms-clients/oakidocs/`:
`deploy-key`, `known_hosts`, `nginx-dms.conf`, `env`. Keep that folder private.

## Step 3. On the nginx server (192.168.3.50)

Print the config the script made, on your own computer:

```bash
cat ~/.dms-clients/oakidocs/nginx-dms.conf
```

Copy that text to the nginx server and save it as `/etc/nginx/sites-available/dms`. Then, on
the nginx server:

```bash
sudo ln -s /etc/nginx/sites-available/dms /etc/nginx/sites-enabled/dms
sudo nginx -t && sudo systemctl reload nginx
```

Adjust the paths if your nginx layout is different.

On the Docker server, ports 3000, 8080 and 8081 are published on 192.168.0.51 so nginx can
reach them. Restrict them to 192.168.3.50 after the first deploy works. Note that a normal host
firewall rule does not stop traffic to Docker's published ports: see the runbook, step A3.

## Step 4. On the GitHub website

Repository -> Settings -> Environments -> New environment, named **`client-oakidocs`**.

Variables:

| Name | Value |
|---|---|
| `DEPLOY_HOST` | `192.168.0.51` |
| `DEPLOY_USER` | `oak` |
| `DEPLOY_PATH` | `/opt/dms` |
| `PUBLIC_URL` | `http://oakidocs.ois.local` |

Secrets (print each value on your own computer and paste it in):

```bash
cat ~/.dms-clients/oakidocs/deploy-key       # -> secret DEPLOY_SSH_PRIVATE_KEY
cat ~/.dms-clients/oakidocs/known_hosts      # -> secret DEPLOY_SSH_KNOWN_HOSTS
```

Also add yourself as a **required reviewer** on this environment, so every deploy needs approval.

## Step 5. Runner, merge and deploy

1. **Runner:** install a self-hosted GitHub runner on a machine that can reach 192.168.0.51
   over SSH. Put its label in the `runner:` field of `oakidocs` in `infra/clients.yml`.
2. **Name resolution:** `oakidocs.ois.local` must resolve from the runner (the deploy's last
   check requests it).
3. **Merge:** review and commit the repository changes, and merge to `main`, so the images
   are built and scanned.
4. **Deploy:** GitHub -> Actions -> **Deploy** -> Run workflow, and type `oakidocs` in the
   `clients` box. The approver clicks Approve.

The first deploy builds three images, including the 5.7 GB OCR one with no cache, so it takes
a while.

## Step 6. Afterwards

- **Change the `oak` password.** It was shared in chat and should be treated as exposed. Once
  key login works, turn off password login for `oak` on 192.168.0.51.
- **Review the server's `.env`** (`/opt/dms/infra/production/.env`):
  - `KEYCLOAK_SUPERADMIN_USERNAME` - the default is `superadmin@gmail.com`; use a real address.
  - `SMTP_USERNAME` / `SMTP_PASSWORD` - the Gmail address and a Google app password; no mail is sent without them.
  - `ALERT_EMAIL_TO` - blank means monitoring alerts are not emailed.
- **Take a first backup** on the Docker server and copy it off the server:
  `cd /opt/dms/infra/production && ./backup.sh --label first`
- Optionally set `--cleanup` the next time you use the script so the local copies of the `.env`
  and key are removed once they are on the server and in GitHub.

## If something fails

| Where | Symptom | Likely cause |
|---|---|---|
| Step 2 | cannot create `/opt/dms` | step 1 not done: the `oak` user needs write access |
| Step 2 | host key does not match | do not continue: check you are connecting to the right server |
| Deploy | stops before changing anything, naming a missing secret or `CHANGE_ME` | the server's `.env` is incomplete |
| Deploy | last check fails on `/login` | nginx not set up, or the name does not resolve from the runner |
| Deploy | an image scan fails | a fixable CRITICAL vulnerability: see the scan output |
| After a failed upgrade | the old version is back but the data looks wrong | restore the pre-upgrade backup: `./restore.sh --list` |

More detail: `docs/clients/oakidocs.md` (this client), `docs/multi-client-deployment.md`
(the general process), `docs/operations.md` (backups, restore, secrets).
