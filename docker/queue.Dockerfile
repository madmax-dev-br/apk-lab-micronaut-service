# The queue image: JDK build stage, JRE runtime stage.
#   docker build -f docker/queue.Dockerfile -t apk-lab-queue .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY api api
COPY runner runner
COPY queue queue
RUN ./gradlew --no-daemon :queue:installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 apklab && mkdir -p /data && chown apklab /data
WORKDIR /app
COPY --from=build /src/queue/build/install/queue /app/queue
USER apklab
ENV QUEUE_HOST=0.0.0.0 \
    QUEUE_PORT=8080 \
    QUEUE_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=50"
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["/app/queue/bin/queue"]
