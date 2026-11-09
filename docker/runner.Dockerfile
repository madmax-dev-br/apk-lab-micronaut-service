# The runner image: JDK build stage, then a JRE + Node.js runtime stage with the sidecar and the pinned APK Web Lab runtime.
#   docker build -f docker/runner.Dockerfile -t apk-lab-runner .
# The runtime is downloaded during the build (sidecar/fetch-runtime.mjs, SHA-256 verified); pass --build-arg FETCH_RUNTIME=0 and mount a
# runtime at /app/vendor/apk-lab (or set APK_LAB_RUNTIME_DIR) to build offline.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY api api
COPY runner runner
RUN ./gradlew --no-daemon :runner:installDist -x test

FROM node:22-bookworm-slim AS runtime
ARG FETCH_RUNTIME=1
WORKDIR /app
COPY sidecar sidecar
RUN if [ "$FETCH_RUNTIME" = "1" ]; then node sidecar/fetch-runtime.mjs; else mkdir -p vendor/apk-lab; fi

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 apklab
COPY --from=runtime /usr/local/bin/node /usr/local/bin/node
WORKDIR /app
COPY --from=build /src/runner/build/install/runner /app/runner
COPY --from=runtime /app/sidecar /app/sidecar
COPY --from=runtime /app/vendor /app/vendor
RUN mkdir -p /app/work && chown -R apklab /app/work
USER apklab
ENV RUNNER_HOST=0.0.0.0 \
    RUNNER_PORT=8081 \
    RUNNER_SIDECAR_SCRIPT=/app/sidecar/vm-host.mjs \
    APK_LAB_RUNTIME_DIR=/app/vendor/apk-lab \
    RUNNER_WORK_DIR=/app/work \
    JAVA_OPTS="-XX:MaxRAMPercentage=25"
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s CMD ["node", "-e", "fetch('http://127.0.0.1:8081/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
ENTRYPOINT ["/app/runner/bin/runner"]
