# GMshield-backend

REST API and system of record for GMshield. Stack: Java 27 and Spring Boot 4.2 as one modular
monolith (Spring Modulith), built with Gradle, on PostgreSQL and Redis.

## Versions

Checked on 2026-11-12. Libraries are the newest published on Maven Central that day, release
candidates included; they move to general releases when those exist. Gradle and every container
image are the newest release at least two weeks old that day, and images are also pinned by digest.

| What | Version | Where it is set |
|---|---|---|
| Java | 27 (Temurin 27.0.1) | `build.gradle.kts` (toolchain), `Dockerfile` |
| Spring Boot | 4.2.0-RC2 | `build.gradle.kts` |
| Spring Modulith | 2.2.0-RC1 | `build.gradle.kts` |
| springdoc-openapi | 3.2.0 | `build.gradle.kts` |
| JaCoCo | 0.8.16 | `build.gradle.kts` |
| Gradle | 9.8.2 (released 2026-10-19), wrapper committed with the distribution checksum | `gradle/wrapper/gradle-wrapper.properties` |
| Build image | `eclipse-temurin:27.0.1.0-jdk-alpine` (JDK released 2026-10-24) | `Dockerfile` |
| Runtime image | `eclipse-temurin:27.0.1.0-jre-alpine` | `Dockerfile` |
| PostgreSQL (development) | `postgres:18.6-alpine` (released 2026-08) | `dev/compose.yaml` |
| Redis (development) | `redis:8.10.3-alpine` (released 2026-10-26) | `dev/compose.yaml` |
| Qdrant (development) | `qdrant/qdrant:v1.20.0` (released 2026-10-22) | `dev/compose.yaml` |

Everything else (Tomcat, Flyway, the PostgreSQL driver, Lettuce, Jackson, JUnit) is the version
that Spring Boot 4.2.0-RC2 manages.

## Structure

Base package `io.github.gmshield`. Each direct sub-package is a module, split into three parts:

| Part | Holds | Who may use it |
|---|---|---|
| `web` | controllers, filters, request and response types | its own module |
| `service` | the module's logic and the types it offers | its own module and other modules |
| `data` | persistence | its own module |

A module names the modules it may use in its `package-info.java`
(`@ApplicationModule(allowedDependencies = "shared :: service")`). `ModuleBoundaryTest` fails the
build when a class uses another module's `web` or `data`, or a module that is not allowed.
Only `shared` exists so far: the error model, trace ids, log lines, start-up checks and the
contract versions.

- **APIs.** Public under `/api/v1`, internal under `/internal/v1`, camelCase JSON.
- **No credential yet.** `/internal/v1` and both OpenAPI documents (`/v3/api-docs/public`,
  `/v3/api-docs/internal`) answer without a credential until the service credential story.
- **Contract.** `openapi/public.json` and `openapi/internal.json` are generated and committed, each
  with a version (`ContractVersions`). `GET /api/v1/version` and `GET /internal/v1/version` answer
  the version of their document. The build fails when a generated document differs from its file:
  after an intended change run `./gradlew test -PupdateOpenApi`, raise the version, commit the file.
- **Errors.** Every error is `application/problem+json` with `type`, `title`, `status`,
  `category`, `code` (`category.reason`), `params` and `traceId`, and nothing internal. A module
  throws `ApiException` with an `ApiProblem`.
- **Logs.** One JSON object per line with `timestamp`, `level`, `traceId`, `unit` and `event`.
  Own code logs an event name and ids or numbers as key-value pairs
  (`log.atInfo().addKeyValue("pages", 12).log("material.accepted")`): never content, never a secret.
  The trace id comes from a valid `traceparent` header, otherwise it is new.
- **Health.** `GET /actuator/health` answers the status only; no other actuator path answers.

## Settings

Read from the environment and checked before anything starts. A refused start prints one line
that names the settings, never a value, and exits with status 78.

| Setting | Meaning |
|---|---|
| `GMSHIELD_DB_URL` | JDBC address of PostgreSQL (required) |
| `GMSHIELD_DB_USER` | database user (required) |
| `GMSHIELD_DB_PASSWORD` | database password (required, secret) |
| `GMSHIELD_REDIS_HOST` | Redis host (required) |
| `GMSHIELD_REDIS_PORT` | Redis port (default 6379) |
| `GMSHIELD_REDIS_PASSWORD` | Redis password (required, secret) |
| `GMSHIELD_MODE` | unset, or `test` |

`GMSHIELD_MODE=test` is set only by the pipeline, never in `.gmshield/pipeline.toml`. It uses
built-in throwaway settings and needs no internet: PostgreSQL at `postgres:5432`, database and
user `gmshield`, password `gmshield-test-mode`; Redis at `redis:6379` without a password. These
values are fixed: in test mode the settings above are not read, so it cannot be pointed at another
host. Test mode refuses to start when a secret above is present. Any other value of
`GMSHIELD_MODE` is refused.

## Build and start

Neither Java nor Gradle needs to be installed: everything builds and runs in Docker.

```sh
docker build --target test .                                               # tests and every build guard
docker build --target test-reports --output type=local,dest=out .          # test results, coverage, classes
docker build -t gmshield-backend .                                         # the production image
dev/start                                                                  # data services and applications
dev/stop
```

After an intended change of an API, regenerate `openapi/*.json` with the pinned build image on
the mounted repository (it leaves `build/` and `.gradle/` behind, both ignored by git):

```sh
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GRADLE_USER_HOME=/tmp/gradle \
  -v "$PWD":/workspace -w /workspace \
  eclipse-temurin:27.0.1.0-jdk-alpine@sha256:a63e42ef2a577cdb0802b73bb6f8fa2c3b0ebf1b4f00f29eb22e7f4d83b234f7 \
  ./gradlew --no-daemon test -PupdateOpenApi
```

[`dev/README.md`](dev/README.md) describes the shared development setup, used by the frontend and
the worker as well. With a JDK 27 installed, `./gradlew check` runs the same tests without Docker
(they need neither PostgreSQL nor Redis).

The production image runs as user 10001, writes only to `/tmp`, and works with a read-only root
filesystem (`docker run --read-only`).

## Rules for changes

- **Migrations** are Flyway scripts in `src/main/resources/db/migration`, named
  `V<number>__<what>.sql`. A script that has been merged is never edited: a change is a new script.
  The database enforces structure only (no triggers, procedures or row-level security). Each
  migration keeps the previous backend release working, because every application deploys and
  rolls back alone: add first, remove in a later release.
- **Understood in one release, written in the next.** A new state, kind, error code or key is
  first released as something every application accepts and handles, and only the following
  release starts to write or send it. A rollback of one application then never meets a value it
  does not know.

## Branches and delivery

| Branch | What happens on push |
|---|---|
| `develop` | roberto-server runs unit tests, secret scan, SAST, SCA/SBOM/IaC, image scan and an active DAST scan; the quality gate decides whether this code may go to `main`. |
| `main` | Only code that already passed on `develop` is accepted. roberto-server builds and publishes the production image, deploys it, runs a passive DAST scan, and rolls back on failure. |

Work on feature branches, merge into `develop`, then open a pull request from `develop` to `main`.
Reports for every run are kept by the pipeline; see [GMshield-pipeline](https://github.com/GMshield/GMshield-pipeline).

## Pipeline contract

The pipeline reads [`.gmshield/pipeline.toml`](.gmshield/pipeline.toml) and builds the multi-stage
[`Dockerfile`](Dockerfile): the `test` stage runs the unit tests, `test-reports` exports the test
results, the coverage report and the compiled classes (read by SonarQube through
[`sonar-project.properties`](sonar-project.properties)), and the production image is the last stage.

Until Story 1.4 gives the pipeline a throwaway PostgreSQL and Redis, a run on `develop` fails at
the staging start: the backend does not report healthy without them. That blocks promotion to
`main` and nothing else.
