# syntax=docker/dockerfile:1
#
# Build context for this Dockerfile is frontend/ itself, e.g.:
#   docker build -t oakdms-frontend -f frontend/Dockerfile frontend
# (or point a compose service's `build.context` at ./frontend).

# ---- deps: install dependencies with bun, cached separately from source ----
FROM oven/bun:1 AS deps
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

# ---- build: compile the Next.js app ----
FROM oven/bun:1 AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
# NEXT_PUBLIC_* values are inlined into the browser bundle by `next build`
# (see lib/api-config.ts) - they can't be changed at container runtime, and
# .dockerignore excludes .env*, so they must arrive as build args. The
# defaults target the production topology (docs/deployment.md): nginx
# serves the frontend, dms-core (/api/) and Keycloak (/realms/) from ONE
# origin, so empty = same-origin relative "/api/v1/..." calls and the image
# contains no hostname at all. Override both for a split-origin setup, e.g.
#   --build-arg NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ARG NEXT_PUBLIC_API_BASE_URL=""
ARG NEXT_PUBLIC_KEYCLOAK_ACCOUNT_URL="/realms/oak-dms/account"
# Same-origin by default, like the two above: nginx proxies /grafana/ to the
# Grafana container (infra/production/nginx/dms.conf), so the System
# Monitoring page can frame it without a cross-site cookie.
ARG NEXT_PUBLIC_GRAFANA_URL="/grafana"
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_KEYCLOAK_ACCOUNT_URL=${NEXT_PUBLIC_KEYCLOAK_ACCOUNT_URL}
ENV NEXT_PUBLIC_GRAFANA_URL=${NEXT_PUBLIC_GRAFANA_URL}
RUN bun run build

# Turbopack (the default `next build` bundler on Next 16) has known
# non-determinism bugs that can silently drop a route from the build
# output on some runs and not others (vercel/next.js#87283). Rather than
# ship a broken image and only find out from a 404 in the browser, fail
# the build here if a known route's server output is missing. Add more
# paths below as needed.
RUN test -d ".next/server/app/admin/users/[userId]/edit" || \
    (echo "BUILD INCOMPLETE: /admin/users/[userId]/edit missing from .next/server/app - Turbopack build flake, retry the build" && exit 1)

# ---- runtime: minimal standalone server ----
FROM oven/bun:1 AS runtime
RUN groupadd -r oakdms && useradd -r -g oakdms oakdms
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# .next/standalone contains a self-contained server.js plus only the
# node_modules the app actually traces as needed - no full install required.
COPY --from=build --chown=oakdms:oakdms /app/.next/standalone ./
COPY --from=build --chown=oakdms:oakdms /app/.next/static ./.next/static
COPY --from=build --chown=oakdms:oakdms /app/public ./public

USER oakdms

EXPOSE 3000
ENTRYPOINT ["bun", "server.js"]

# oven/bun ships neither curl nor wget, so the check uses bun's own fetch.
# /login is public (proxy.ts PUBLIC_PATHS), so it answers 200 without a
# session instead of redirecting.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["bun", "-e", "fetch('http://127.0.0.1:3000/login').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"]