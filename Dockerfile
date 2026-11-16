# GMshield backend. Stages the pipeline uses (GMshield-pipeline, "Repository contract"):
#   test          runs the unit tests and every build guard; a failure fails the build
#   test-reports  test results, coverage, compiled classes and dependency jars, exported for the
#                 artifacts and SonarQube
#   integration   the integration tests, run as a container beside a throwaway PostgreSQL and Redis
#                 (.gmshield/pipeline.toml [test]); built here, run offline
#   production    the image that runs (last stage)
# Images are pinned by version and digest: the newest release at least two weeks old on 2026-11-12.

FROM eclipse-temurin:27.0.1.0-jdk-alpine@sha256:a63e42ef2a577cdb0802b73bb6f8fa2c3b0ebf1b4f00f29eb22e7f4d83b234f7 AS build
WORKDIR /workspace
ENV GRADLE_USER_HOME=/gradle-home \
    GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.console=plain"
# Wrapper first, then the build files, then the sources: a source change reuses the dependency layers.
COPY gradlew ./
COPY gradle/ gradle/
RUN ./gradlew --version
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew dependencies --configuration testRuntimeClasspath > /dev/null
COPY openapi/ openapi/
COPY src/ src/

FROM build AS test
RUN ./gradlew check bootJar sonarLibraries

# The pipeline runs this image with GMSHIELD_MODE=test on an internal network (no internet) where the
# services answer as postgres:5432 and redis:6379: everything it needs is compiled and fetched here.
FROM build AS integration
RUN ./gradlew integrationTestClasses integrationTestDependencies
CMD ["./gradlew", "--offline", "integrationTest"]

FROM scratch AS test-reports
COPY --from=test /workspace/build/test-results/test/ /junit/
COPY --from=test /workspace/build/reports/jacoco/test/jacocoTestReport.xml /jacoco.xml
COPY --from=test /workspace/build/classes/java/main/ /classes/
COPY --from=test /workspace/build/sonar-libraries/ /libraries/
COPY --from=test /workspace/build/openapi/ /openapi/

FROM eclipse-temurin:27.0.1.0-jre-alpine@sha256:a0f3b523fd89e242ce302715ce367f68e9073c59274e65746d0791c750f38de3 AS production
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="gmshield-backend" \
      org.opencontainers.image.revision="${GIT_SHA}"
RUN addgroup -S -g 10001 gmshield \
    && adduser -S -D -H -u 10001 -G gmshield -s /sbin/nologin gmshield \
    && install -d -o 10001 -g 10001 -m 700 /var/lib/gmshield/files
WORKDIR /app
COPY --from=test /workspace/build/libs/gmshield-backend.jar /app/gmshield-backend.jar
# Not root, and nothing written outside /tmp and the encrypted file store: the root filesystem can be
# read-only. /tmp is a volume so that `--read-only` works without any other option; a tmpfs on /tmp
# replaces it. /var/lib/gmshield/files (owner 10001, mode 700) is where the pipeline mounts the named
# volume gmshield-backend-files; Docker fills a new volume from this folder, owner included.
USER 10001:10001
VOLUME ["/tmp"]
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/gmshield-backend.jar"]
