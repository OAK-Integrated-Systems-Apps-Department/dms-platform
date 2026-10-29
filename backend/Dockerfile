# syntax=docker/dockerfile:1
#
# Build context for this Dockerfile is backend/ (not backend/dms-core/),
# since dms-core's pom.xml now has a parent reference to ../pom.xml
# (the dms-platform-backend aggregator) that must be present in the build
# context for Maven to resolve it locally rather than fail looking for a
# published artifact that doesn't exist.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY dms-core/pom.xml dms-core/pom.xml
RUN mvn -q -B -pl dms-core -am dependency:go-offline
COPY dms-core/src dms-core/src
RUN mvn -q -B -pl dms-core -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S oakdms && adduser -S oakdms -G oakdms
# Fonts for document watermarking (module DW).
#
# This image ALREADY has fontconfig, freetype and font-dejavu - verified by
# inspecting the base image, not assumed - so java.awt renders Latin, Greek
# and Cyrillic out of the box. What it does not have is any coverage for
# CJK, Devanagari, Thai, Hebrew, Bengali or Tamil, and only partial Arabic.
#
# That gap matters because a watermark carries the name of the person who
# asked for the copy. A font that can't draw a character doesn't fail - it
# draws a hollow box - so a missing script means somebody's name appears as
# tofu on a document they are about to send out.
#
# Measured against the base image (fc-list :lang=...), these packages
# are what actually buy coverage; font-noto / font-noto-extra add ~260 files
# and no new scripts at all, so they are deliberately NOT installed:
#
#   package               adds
#   font-noto-cjk         Chinese, Japanese, Korean
#   font-noto-devanagari  Hindi and related
#   font-noto-arabic      full Arabic (the base has only partial cover)
#   font-noto-thai        Thai
#   font-noto-hebrew      Hebrew
#   font-noto-bengali     Bengali
#   font-noto-tamil       Tamil
#
# POLICY for a script with no font here: the request is refused with 422
# WATERMARK_SCRIPT_UNSUPPORTED and a message telling the user to ask an
# administrator to install a font package. It is never drawn as hollow
# boxes. A refusal is visible and fixable in one line; a document that went
# out with tofu where a name should be is neither.
#
# Cost: ~181MB in /usr/share/fonts, of which the last four packages are
# ~36MB - cheap for the share of the world's names they cover. Drop any
# line whose scripts no tenant needs; adding a package is all that is
# required to support a script.
# FontProvider tries dms.watermark.font-families first and then every family
# installed here, so a package does not have to be named in the
# configuration to be used - the CJK family is "Noto Sans CJK SC", which
# nobody would think to configure by hand.
#
# Kept as an ARG on one line so CI can read the exact list back out of this
# file and run the rendering tests against the same font stack, rather than
# repeating it in a workflow where the two would quietly drift apart. Same
# reason the workflows already read the JDK version from here.
ARG WATERMARK_FONT_PACKAGES="font-noto-cjk font-noto-devanagari font-noto-arabic font-noto-thai font-noto-hebrew font-noto-bengali font-noto-tamil"
# apk upgrade first: the base image can lag the Alpine repository, and a fixed CRITICAL in a
# base library (brotli-libs, CVE-2026-111497) would otherwise fail the image scan until the
# base is rebuilt.
RUN apk upgrade --no-cache && apk add --no-cache ${WATERMARK_FONT_PACKAGES} && fc-cache -f
WORKDIR /app
COPY --from=build /build/dms-core/target/dms-core.jar app.jar
# FILE_SYSTEM storage's default STORAGE_FILESYSTEM_BASE_PATH (see
# application.yml / docs/storage-architecture.md) - created here, owned by
# the non-root oakdms user, so a named volume mounted at /data in
# docker-compose.yml inherits this ownership instead of root:root.
RUN mkdir -p /data/dms/documents && chown -R oakdms:oakdms /data
# Development key file for encryption at rest (dms.encryption.provider=local-dev),
# owned by oakdms so the named volume mounted here starts out writable. Production
# uses OpenBao and never writes here.
RUN mkdir -p /var/lib/dms-encryption && chown oakdms:oakdms /var/lib/dms-encryption && chmod 700 /var/lib/dms-encryption
USER oakdms

EXPOSE 8080
# -Djava.awt.headless=true: there is no display here, and without it AWT can
# try to reach one the moment the watermark renderer touches a font.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "-Djava.awt.headless=true", "-jar", "app.jar"]

# The "core" group (application.yml) is every health indicator EXCEPT
# ocrJobToken - an OCR-only misconfiguration must not roll back an
# otherwise-healthy release the way a lost database connection would.
# Root /actuator/health (unauthenticated, same as this) still reports
# ocrJobToken for anyone checking overall system state by hand.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/core | grep -q '"status":"UP"' || exit 1
