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
Three modules exist so far: `shared` (the error model, trace ids, log lines, start-up checks, the
contract versions and `Ids`, which mints every id as a UUID v7), `vault` (encryption at rest,
below) and `accounts` (sign-in with Google, accounts and sessions, below; it may use `shared` and
`vault`).

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
- **Encryption at rest (`vault`, AD-19).** Every module stores content, personal data and file
  pieces through `vault.service`:
  - `Vault.encrypt(binding, bytes)` / `decrypt`: AES-256-GCM with a random 96-bit nonce under the
    owner's data key. The `Binding` is entity, field, row id and owner id; they go, length-prefixed,
    into the AAD, so a value read under another row, field, entity or owner fails. A stored value is
    `format(1) ‖ dataKeyVersion(4) ‖ nonce(12) ‖ ciphertext ‖ tag(16)`.
  - One data key per owner, created on first use, kept in `vault_data_key` wrapped by the master key
    (AAD: owner and version); unwrapped keys live in a bounded in-memory map and are never logged.
  - `Vault.fingerprint(entity, field, value)` for exact lookups (no SQL text search) and
    `Vault.tokenHash(purpose, token)` for stored tokens: HMAC-SHA256 under subkeys derived by HKDF
    from the index key, separated by purpose and by entity and field.
  - `FileStore.writePiece` / `readPiece`: one encrypted file per piece under `GMSHIELD_FILES_DIR`,
    `<owner>/<file>/<index>.piece`, bound to owner, file id and index, written under a temporary name
    and renamed.
  - Any failed check (changed byte, moved value, unknown version, wrong master key) throws
    `IntegrityException`: the problem `integrity.failed` (500, no params) and no content. It logs ids
    and the entity and field names only: `vault.integrity_failed` when a stored value fails, and
    `vault.data_key_failed` when an owner's data key cannot be unwrapped (a wrong master key). When
    writing, only the second line appears; when reading, both do.
  - `ModuleBoundaryTest` fails the build when a class outside `vault` uses `javax.crypto`,
    `java.security.spec`, `MessageDigest`, `Mac`, `Signature`, `KeyPairGenerator`, `KeyFactory`,
    `KeyStore`, BouncyCastle, Tink, Spring Security crypto, Spring's `DigestUtils`, Guava hashing or
    commons-codec digests (fixtures prove the rule fails). A checksum, such as an upload's SHA-256,
    goes through a `vault` method too.
  - `Vault.seal(entity, rowId, values...)` / `verifySeal`: the keyed signature (HMAC-SHA256 under a
    subkey of the entity) over the row id and the sealed values, compared in constant time; a mismatch
    logs `vault.seal_failed` (entity and row id) and throws `IntegrityException`.
    `Vault.pkceChallenge(verifier)` gives the PKCE S256 challenge.
  - Test mode only: `POST /internal/v1/test-mode/nfr1-probe` and `GET .../{id}` (left out of the
    OpenAPI documents) store and read back the pipeline's NFR-1 text as one `vault_probe` row and
    one file piece, then announce `Nfr1ProbeSeeded`: `accounts` also stores the text as a real
    account's Google id and email address, and reads it back. The table comes from
    `db/test-mode-migration`, a location only test mode adds.
- **Sign-in with Google (`accounts`, AD-8, AD-20).** The backend alone calls Google, with the OIDC
  code flow (scopes `openid email`, PKCE S256, state and nonce). Routes, all `no-store`:
  - `POST /api/v1/sign-in/google` issues an attempt token (10 minutes) and the authorization URL.
    Redis keeps the attempt under `gmshield:sign-in:<hex of vault.tokenHash("sign-in", token)>`,
    600 s. The state, the nonce and the PKCE verifier are `tokenHash` of the token under the
    purposes `sign-in-state`, `sign-in-nonce` and `sign-in-pkce`, so Redis holds nothing that
    completes a sign-in.
  - `POST /api/v1/sign-in/google/callback` (`attemptToken`, `state`, `code`, `error`, relayed by the
    frontend) takes the attempt out of Redis in one step (`GETDEL`). No attempt, a wrong state, an
    expired or reused attempt: `request.sign_in_expired` (400). Google's `error`, a failed exchange
    or an unacceptable ID token: `request.sign_in_refused` (400). Nothing is created either way. The
    ID token comes straight from Google's token endpoint over TLS, so its signature is not checked
    (OIDC Core 3.1.3.7, item 6); `iss`, `aud` (and `azp`), `exp`, `nonce` and `email_verified=true`
    are. A known Google id gets a session (`signed_in`); a changed email address is updated, the
    seal version rises and the account is sealed again. An unknown one is encrypted for a newly
    minted account id and kept in the attempt (`username_required`).
  - `POST /api/v1/sign-in/complete` (`attemptToken`, `username`, `termsAccepted`) creates the
    account and a session. Usernames: 3 to 24 of `A-Z a-z 0-9 _ . -`, unique whatever the case
    (`request.username_invalid` 400 with `min` and `max`, `request.username_taken` 409, kept by a
    unique index on the fingerprint of the NFC lower-case form), shown as typed. Unticked terms:
    `request.terms_required` (400). The attempt stays for another try.
  - `GET /api/v1/me` and `DELETE /api/v1/session` read the session token from `X-Session-Token`
    (never a cookie). No session, an ended one or an account that is not active:
    `session.required` (401). Sign-out deletes the session and its entry in the account's set; it
    answers 204 whether or not the session existed.
  - The table `account` (`V2__account.sql`) stores only the username, the email address and the
    Google id, each encrypted with `Binding("account", field, id, id)` and fingerprinted, plus tier
    (`ordinary`), state (`active`), `confirmed`, `terms_version`, `terms_accepted_at`,
    `seal_version` and `seal`. The seal covers the id, tier, state, the Google-id, username and email
    fingerprints, `confirmed` and `seal_version`, and is checked on every load: a mismatch is
    `integrity.failed` (500) and grants nothing.
  - Sessions: `gmshield:session:<hex of vault.tokenHash("session", token)>` holds the account id,
    the seal version and the creation time for 7 days; `gmshield:session:account:<account id>` is
    the set of an account's session hashes. Rotation, the idle timeout and the seal-version check
    come with Story 1.10.
  - Test mode: a stand-in replaces Google. Its authorization URL is the frontend's own callback,
    relative (`/auth/google/callback?state=<real state>&code=stand-in.1.gm@example.test`); any code
    `stand-in.<sub>.<email>` gives that identity, any other is refused.

## Settings

Read from the environment and checked before anything starts. A refused start prints one line
that names the settings, never a value, and exits with status 78.

| Setting | Meaning |
|---|---|
| `GMSHIELD_DB_URL` | JDBC address of PostgreSQL (required); in production it ends in `?sslmode=verify-full&sslrootcert=/run/gmshield/certs/ca.crt` |
| `GMSHIELD_DB_USER` | database user (required; `gmshield_backend` in production) |
| `GMSHIELD_DB_PASSWORD` | database password (required, secret) |
| `GMSHIELD_REDIS_HOST` | Redis host (required) |
| `GMSHIELD_REDIS_PORT` | Redis port (default 6379) |
| `GMSHIELD_REDIS_USERNAME` | Redis account (required; `backend` in production) |
| `GMSHIELD_REDIS_PASSWORD` | Redis password (required, secret) |
| `GMSHIELD_REDIS_TLS` | `true` (default): TLS verified, chain and host name, against `/run/gmshield/certs/ca.crt`; `false` only in the development setup |
| `GMSHIELD_VAULT_MASTER_KEY` | 32 random bytes in Base64 (required, secret): wraps every data key. Losing or changing it loses every stored value; no off-server copy exists until Epic 10 |
| `GMSHIELD_VAULT_INDEX_KEY` | 32 random bytes in Base64 (required, secret, different from the master key): source of the fingerprint and token-hash subkeys |
| `GMSHIELD_FILES_DIR` | the encrypted file store (default `/var/lib/gmshield/files`, where the pipeline mounts the volume `gmshield-backend-files`) |
| `GMSHIELD_GOOGLE_CLIENT_ID` | the Google OAuth client id (required, secret): entered by hand in `backend.env` |
| `GMSHIELD_GOOGLE_CLIENT_SECRET` | the Google OAuth client secret (required, secret): entered by hand in `backend.env` |
| `GMSHIELD_PUBLIC_URL` | the frontend's origin as the browser sees it, `https` with no path, or `http` only on `localhost`, `127.0.0.1` or `[::1]` (required; `.gmshield/pipeline.toml`): Google redirects to `<it>/auth/google/callback`, which must be a redirect URI of the OAuth client |
| `GMSHIELD_MODE` | unset, or `test` |

`GMSHIELD_MODE=test` is set only by the pipeline, never in `.gmshield/pipeline.toml`. It uses
built-in throwaway settings and needs no internet: PostgreSQL at `postgres:5432`, database and
user `gmshield`, password `gmshield-test-mode`; Redis at `redis:6379` without a password. These
values are fixed: in test mode the settings above are not read, so it cannot be pointed at another
host. Test mode uses plain connections and needs no certificate. Its vault keys are random for each
start and held only in memory, and its file store is `/var/lib/gmshield/files`. Test mode refuses
to start when a secret above is present. Any other value of `GMSHIELD_MODE` is refused. A vault key
that is not 32 bytes of Base64, or the same key twice, is refused with `setting.invalid`, as is a
public URL that is not an `https` origin (plain `http` only on `localhost`, `127.0.0.1` or `[::1]`,
where browsers still keep the `__Host-` cookies). Test mode ignores `GMSHIELD_PUBLIC_URL` (the
pipeline's staging copy has it) and refuses the Google client id or secret like any other secret.

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

The production image runs as user 10001, writes only to `/tmp` and `/var/lib/gmshield/files` (created
owned by 10001, mode 700, so that a new named volume mounted there gets that owner), and works with a
read-only root filesystem (`docker run --read-only`).

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

In production (Story 1.6) the backend publishes no host port and joins `gmshield-app`,
`gmshield-data` (PostgreSQL), `gmshield-work` (Redis) and `gmshield-outbound`, runs as 10001 with a
read-only root filesystem and every capability dropped, and finds the private authority's
certificate at `/run/gmshield/certs/ca.crt`. Its settings, written once by GMshield-pipeline's
`deploy/data-credentials.sh`, live in `~/gmshield-ci/secrets/backend.env` on the server: its own
PostgreSQL role `gmshield_backend` (database `gmshield`, `sslmode=verify-full`), its own Redis
account `backend` (keys `gmshield:*` only), the two vault keys, and the Google OAuth client id and
secret, which Dalton enters by hand (the script writes empty placeholders and never replaces a value;
without them the backend refuses to start, `setting.missing`). `GMSHIELD_PUBLIC_URL` is a plain
setting in the contract's `[deploy].env`: `http://localhost:3000`, the address of the frontend through
Dalton's SSH tunnel, until the public domain exists. The contract mounts the named volume
`gmshield-backend-files` at `/var/lib/gmshield/files` (the encrypted file store).

`[nfr1]` in the contract turns on the NFR-1 release gate on `develop`: inside the staging session the
pipeline stores a random text through the test-mode probe route (which also stores it as an account's
Google id and email address), reads it back, and searches `pg_dump`, the file-store volume, a Redis
RDB and every log for it, plain and encoded.

The pipeline reads [`.gmshield/pipeline.toml`](.gmshield/pipeline.toml) and builds the multi-stage
[`Dockerfile`](Dockerfile): the `test` stage runs the unit tests, `test-reports` exports the test
results, the coverage report and the compiled classes (read by SonarQube through
[`sonar-project.properties`](sonar-project.properties)), and the production image is the last stage.

Until Story 1.4 gives the pipeline a throwaway PostgreSQL and Redis, a run on `develop` fails at
the staging start: the backend does not report healthy without them. That blocks promotion to
`main` and nothing else.
