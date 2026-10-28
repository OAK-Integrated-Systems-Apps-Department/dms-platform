#!/usr/bin/env python3
"""Works out which clients a Deploy run goes to, from infra/clients.yml.

Prints GitHub Actions step outputs (`matrix=...`, `count=...`) on stdout, so the
workflow does `python3 .github/scripts/select-clients.py >> "$GITHUB_OUTPUT"`.

Environment:
  EVENT      github.event_name            (push | workflow_dispatch)
  REF_TYPE   github.ref_type              (branch | tag)
  REQUESTED  the "clients" run-form input ("" | "all" | "acme,globex")

Rules
  - a version tag publishes the release images and deploys nowhere: pull-mode
    clients and bundles take it from there
  - a push to main deploys to every `auto: true` client
  - a manual run with nothing typed behaves like a push; "all" is every
    push-mode client; a list names them (an unknown or pull-mode name is an
    error, never a silent skip)
"""
import json
import os
import re
import sys

import yaml

NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
ENVIRONMENT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


with open("infra/clients.yml", encoding="utf-8") as handle:
    clients = (yaml.safe_load(handle) or {}).get("clients") or []

seen = set()
for client in clients:
    name = str(client.get("name", ""))
    if not NAME.match(name):
        fail(f"clients.yml: bad client name {name!r} (lowercase letters, digits, '-')")
    if name in seen:
        fail(f"clients.yml: duplicate client {name!r}")
    seen.add(name)
    if not ENVIRONMENT.match(str(client.get("environment", ""))):
        fail(f"clients.yml: {name}: bad or missing environment")
    if client.get("mode") not in ("push", "pull"):
        fail(f"clients.yml: {name}: mode must be push or pull")
    if not client.get("runner"):
        fail(f"clients.yml: {name}: runner is required")

event = os.environ.get("EVENT", "")
ref_type = os.environ.get("REF_TYPE", "")
requested = os.environ.get("REQUESTED", "").strip()
push_mode = [c for c in clients if c["mode"] == "push"]

if event == "push" and ref_type == "tag":
    chosen = []
elif event == "workflow_dispatch" and requested.lower() == "all":
    chosen = push_mode
elif event == "workflow_dispatch" and requested:
    wanted = [n.strip() for n in requested.split(",") if n.strip()]
    by_name = {c["name"]: c for c in clients}
    for name in wanted:
        if name not in by_name:
            fail(f"unknown client {name!r}; known: {', '.join(sorted(by_name))}")
        if by_name[name]["mode"] != "push":
            fail(f"client {name!r} is pull-mode: CI cannot deploy to it (see docs/multi-client-deployment.md)")
    chosen = [by_name[n] for n in dict.fromkeys(wanted)]
else:
    chosen = [c for c in push_mode if c.get("auto")]

include = [
    {"name": c["name"], "environment": c["environment"], "runner": c["runner"]}
    for c in chosen
]
print("matrix=" + json.dumps({"include": include}))
print(f"count={len(include)}")
print("Deploying to: " + (", ".join(c["name"] for c in include) or "(no clients)"), file=sys.stderr)
