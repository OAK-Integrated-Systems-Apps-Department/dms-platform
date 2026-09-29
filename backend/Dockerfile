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
WORKDIR /app
COPY --from=build /build/dms-core/target/dms-core.jar app.jar
# FILE_SYSTEM storage's default STORAGE_FILESYSTEM_BASE_PATH (see
# application.yml / docs/storage-architecture.md) - created here, owned by
# the non-root oakdms user, so a named volume mounted at /data in
# docker-compose.yml inherits this ownership instead of root:root.
RUN mkdir -p /data/dms/documents && chown -R oakdms:oakdms /data
USER oakdms

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
