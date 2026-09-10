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

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:3000/ || exit 1