#!/usr/bin/env python3
"""Render an OpenBao KV v2 read response into a Docker Compose env file.

    curl ... /v1/secret/data/dms/production/app | render-secrets.py secret-keys.txt > secrets.env

Used by both .github/workflows/deploy.yml and infra/openbao/bao-ops.sh, so
there is exactly one implementation of "which keys, and how they're quoted".

- Emits exactly the keys listed in secret-keys.txt, in that order, and
  fails if any is missing - a deploy must never silently run without one.
- Values are single-quoted: Compose treats single-quoted .env values
  literally, so a '$' in a generated secret is never interpolated.
- Refuses values that can't be represented that way (a single quote or a
  newline) instead of writing a subtly different secret.
- Never prints a secret value; error messages name keys only.
"""
import json
import sys


def load_keys(path):
    with open(path, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip() and not line.lstrip().startswith("#")]


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: render-secrets.py <secret-keys.txt>  (OpenBao JSON on stdin)")
    keys = load_keys(sys.argv[1])

    try:
        response = json.load(sys.stdin)
        data = response["data"]["data"]
    except (ValueError, KeyError, TypeError):
        sys.exit("render-secrets: stdin is not an OpenBao KV v2 read response")

    missing = [k for k in keys if k not in data or data[k] is None]
    if missing:
        sys.exit("render-secrets: missing in OpenBao: " + ", ".join(missing))

    bad = [k for k in keys if any(c in str(data[k]) for c in ("'", "\n", "\r"))]
    if bad:
        sys.exit("render-secrets: values contain a quote or newline (regenerate them): " + ", ".join(bad))

    placeholder = [k for k in keys if str(data[k]) == "CHANGE_ME"]
    if placeholder:
        sys.exit("render-secrets: still CHANGE_ME in OpenBao: " + ", ".join(placeholder))

    out = sys.stdout
    out.write("# Rendered from OpenBao secret/dms/production/app - RAM only, do not copy.\n")
    for k in keys:
        out.write(f"{k}='{data[k]}'\n")


if __name__ == "__main__":
    main()
