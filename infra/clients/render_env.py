#!/usr/bin/env python3
"""Builds a client's server-side .env from infra/production/.env.example.

Called by new-client.sh. Every CHANGE_ME gets its own freshly generated value
(hex only, so it is safe inside the URLs the OCR worker builds). No secret is
printed - only the names of the keys that were generated.

    render_env.py --name acme --public-url https://dms.acme.example \
                  --out acme.env [--bind-address 10.0.0.5]
"""
import argparse
import re
import secrets
import sys
from pathlib import Path
from urllib.parse import urlparse

TEMPLATE = Path(__file__).resolve().parents[1] / "production" / ".env.example"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--public-url", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--bind-address", default="", help="set when nginx runs on another server")
    args = ap.parse_args()

    public = args.public_url.rstrip("/")
    parsed = urlparse(public)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        sys.exit("--public-url must look like http(s)://host")

    values = {
        "PUBLIC_URL": public,
        # Browsers only send Secure cookies over HTTPS.
        "AUTH_COOKIE_SECURE": "true" if parsed.scheme == "https" else "false",
        "GRAFANA_ROOT_URL": public + "/grafana/",
        "MONITORING_CLIENT": args.name,
        "MONITORING_ENVIRONMENT": "production",
    }
    enable = {"PROXY_BIND_ADDRESS": args.bind_address} if args.bind_address else {}

    text = TEMPLATE.read_text(encoding="utf-8").replace("\r\n", "\n")
    out, generated = [], []
    for line in text.split("\n"):
        m = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line)
        if m:
            key, value = m.groups()
            if key in values:
                line = f"{key}={values[key]}"
            elif value == "CHANGE_ME":
                line = f"{key}={secrets.token_hex(24)}"
                generated.append(key)
        else:
            m = re.match(r"^#([A-Z][A-Z0-9_]*)=(.*)$", line)
            if m and m.group(1) in enable:
                line = f"{m.group(1)}={enable[m.group(1)]}"
        out.append(line)

    header = (
        f"# Server-side .env for the {args.name} client.\n"
        "# Copy to <install dir>/infra/production/.env on its Docker server and chmod 600.\n"
        "# Contains real secrets: never commit it, never send it by chat or email.\n"
        "# Generated from infra/production/.env.example by infra/clients/new-client.sh.\n\n"
    )
    dest = Path(args.out)
    dest.write_text(header + "\n".join(out), encoding="utf-8", newline="\n")
    print("generated secrets for: " + ", ".join(generated))


if __name__ == "__main__":
    main()
