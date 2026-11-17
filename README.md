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
Seven modules exist so far: `shared` (the error model, trace ids, log lines, start-up checks, the
contract versions, the owner context and `Ids`, which mints every id as a UUID v7), `vault` (encryption at rest,
below), `audit` (the append-only audit log, below; it may use `shared`), `jobs` (background work that
survives a restart, below; it may use `shared` and `vault`, and no feature module), `library` (systems and
campaigns, below; it may use `shared` and `vault`), `materials` (materials and the resumable upload of their
files, below; it may use `shared`, `vault` and `library`) and `accounts` (sign-in with Google, accounts and
sessions, below; it may use `shared`, `vault`, `audit` and `library`).

- **APIs.** Public under `/api/v1`, internal under `/internal/v1`, camelCase JSON.
- **Credentials.** `/api/v1` requires the frontend's service credential (Story 1.10, below), and
  `/internal/v1` the worker's (Story 1.12, below), `/internal/v1/version` included. Both OpenAPI
  documents (`/v3/api-docs/public`, `/v3/api-docs/internal`) answer without one.
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
    and renamed; writing an index again replaces the piece. `FileStore.deleteFile(owner, file)` removes
    every piece and the file's folder, and is safe to repeat.
  - `Vault.sha256()`: an incremental SHA-256 (`update`, `digest`, `matches` in constant time) for content
    that streams through, such as an upload's part and whole file (Story 1.13).
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
  - `GET /api/v1/me`, `POST /api/v1/session/renewal` and `DELETE /api/v1/session` read the session
    token from `X-Session-Token` (never a cookie). No session, an ended one or an account that is not
    active: `session.required` (401). Sign-out deletes the session and its entry in the account's set;
    it answers 204 whether or not the session existed.
  - The table `account` (`V2__account.sql`) stores only the username, the email address and the
    Google id, each encrypted with `Binding("account", field, id, id)` and fingerprinted, plus tier
    (`ordinary`), state (`active`), `confirmed`, `terms_version`, `terms_accepted_at`,
    `seal_version` and `seal`. The seal covers the id, tier, state, the Google-id, username and email
    fingerprints, `confirmed` and `seal_version`, and is checked on every load: a mismatch is
    `integrity.failed` (500) and grants nothing.
  - Test mode: a stand-in replaces Google. Its authorization URL is the frontend's own callback,
    relative (`/auth/google/callback?state=<real state>&code=stand-in.1.gm@example.test`); any code
    `stand-in.<sub>.<email>` gives that identity, any other is refused.
- **Sessions that rotate and end (`accounts`, Story 1.10).** The accounts module's own Redis store;
  Spring Session is not used. Redis holds only ids, times, flags and token hashes:
  - `gmshield:session:<session id>`: the account id, the seal version the current token was issued
    under, `createdAt`, `lastSeenAt`, a rising `version`, the current token's hash with a `used`
    flag, and after a renewal the previous token's hash with its `overlapUntil`.
  - `gmshield:session:token:<hex of vault.tokenHash("session", token)>`: the session id, for the
    current token and for every token a renewal retired (those live until the absolute end, so the
    reuse of a token two renewals old is still recognised). `gmshield:session:account:<account id>`
    is the set of an account's session ids, so that all can end at once when Epic 8 or 9 needs it.
  - The record and the current token's pointer live as long as the shorter of what is left of the
    idle and the absolute timeouts; retired pointers live until the absolute end, and the account set
    at least as long as the absolute timeout from its newest session.
    Each replacement is one Lua script that first compares the stored `version`, so two renewals of
    one token can never both issue a token; ending a session removes it whatever its version.
    `lastSeenAt` is written at most once a minute.
  - Sign-in and `POST /api/v1/session/renewal` alone issue a token, as `SessionGrant
    {sessionToken, expiresInSeconds, renewAfterSeconds}`; `expiresInSeconds` is what is left of the
    absolute timeout. A renewal makes the presented token the previous one until now + overlap.
  - The previous token within the overlap serves ordinary calls; a renewal with it is
    `session.already_renewed` (409) and the session continues. After the overlap, once the newer
    token has served a call (or for any older token), it is reuse: the session ends, then
    `session.token_reused` is appended to the audit log (a failed append logs `audit.append_failed`
    and still refuses). The previous token after the overlap while the newer one was never used is
    `session.required` and the session is kept.
  - `lastSeenAt` + idle or `createdAt` + absolute passed: the session ends, `session.required`.
  - An account whose seal version rose above the session's answers `session.renewal_required` (401)
    on every call but a renewal, which issues the next token under the current seal version.
  - A cookie or a Redis record of Story 1.9 is no session (`session.required`), never an error.
- **Library and its owner (`library`, Story 1.11, AD-11).** The library tables `system` and `campaign`
  (`V4__library.sql`) each carry `owner_id`, mapped with Hibernate `@TenantId`: Hibernate fills it on insert
  and filters every query by it, so another owner's row is never read. They are read and written only through
  JPA entities and JPQL (also by id), never native SQL or `JdbcClient`.
  - The owner comes from `shared.service.OwnerContext`, a `ScopedValue` bound for one piece of work and
    gone afterwards. Two places bind it: `accounts.web.LibraryOwnerFilter`, for every request to
    `/api/v1/systems/**`, `/api/v1/campaigns/**`, `/api/v1/uploads/**` and `/api/v1/materials/**`, from the session's account (`X-Session-Token`; no
    session: `session.required` before any controller), and `OwnerContext.callAs`, for work run as one
    owner (a first sign-in). Binding it inside bound work is refused. It never comes from a parameter or
    a body. `shared.data.OwnerTenantResolver` hands it to Hibernate; without an owner it throws, so no
    session opens and no query runs, and no tenant is ever a root.
  - **Every JPA transaction needs an owner.** The JPA transaction manager is the application's only
    `PlatformTransactionManager`, and each transaction it begins (`TransactionTemplate`,
    `@Transactional`) opens a Hibernate session first, so without a bound owner it fails with
    `OwnerContext.NoOwnerException` before it starts, whether or not it touches a library table. Work
    with no owner (for example the backend's own job writes, Story 1.12) must bind an owner with
    `OwnerContext.callAs` or run its JDBC writes under a separate JDBC transaction manager on its own
    connection, as `vault.data.JdbcDataKeyStore` does.
  - Names are `bytea`, encrypted with `Binding("system"|"campaign", "name", id, owner)`. A system's
    `name_fingerprint` (NFC lower-case, trimmed) is unique per owner; a campaign names its system by
    `(owner_id, system_id)`, so it can never point at another owner's system.
  - `POST /api/v1/sign-in/complete` takes an optional `language` (`pt-BR` | `en`, default `pt-BR`) and
    creates the account, one system and one campaign in one transaction, run as the new account: "Sistema
    sem nome" / "Primeira campanha", or "Untitled system" / "First campaign". A failure leaves neither and
    keeps the attempt for a retry with the same account id. The vault's data-key insert commits in its own
    transaction (`REQUIRES_NEW`, on its own connection), because a stored key is cached at once.
  - `GET /api/v1/campaigns/current` (the owner's oldest campaign, with its system), `GET
    /api/v1/campaigns/{id}` and `GET /api/v1/systems/{id}`: another owner's id, an unknown id and a
    malformed id all answer the same `request.not_found` (404). Every answer is `no-store`.
  - JPA settings (`application.yaml`): the dialect is given and Hibernate never reads the database's
    metadata at start, `ddl-auto: none` (the schema is Flyway's), `open-in-view: false`. The unit tests
    start without a database and use in-memory stores (`@Primary`).
- **Materials and the resumable upload (`materials`, Story 1.13, AD-16).** The backend's own tus 1.0.0
  server (no tus library): four requests under `/api/v1/uploads`, behind the session's owner
  (`LibraryOwnerFilter`) and `materials.web.UploadProtocolFilter`, which puts `Tus-Resumable: 1.0.0` and
  `Cache-Control: no-store` on every answer and refuses a request without `Tus-Resumable: 1.0.0` with
  `upload.tus_version` (412, `Tus-Version: 1.0.0`).
  - `POST /api/v1/uploads` (`Upload-Length` 1 to 2 GiB, else `upload.too_large` 413; `Upload-Metadata` with
    `filename`, else `upload.metadata_invalid` 400) creates, in one transaction, a `material` (its name
    encrypted with `Binding("material", "name", id, owner)`), a `material_version` in `uploading` and an
    `upload`, and answers 201 with a relative `Location` and `Upload-Expires` (creation + 6 h, fixed).
  - `HEAD /api/v1/uploads/{id}` answers `Upload-Offset`, `Upload-Length` and `Upload-Expires`.
  - `PATCH /api/v1/uploads/{id}` (`application/offset+octet-stream`, `Upload-Offset`, `Upload-Checksum:
    sha256 <Base64>`, at most 90 MiB and never past the length, else 413 before anything is written). One
    part at a time: a conditional update takes a 2-minute lease (`busy_token`, `busy_until`), renewed before
    each piece; a second part answers `upload.busy` (423). The first part's first 4 KiB (the whole file
    when it is shorter) decide the format from content alone; a first part shorter than that is
    `request.invalid` and changes nothing (PDF, JPEG, PNG, WebP, TIFF, HEIC by signature, then UTF-8 text without control
    characters, Markdown when the name ends in `.md` or `.markdown`, then BMP); anything else is
    `upload.format_refused` (415, `params.accepted`) and the same transaction deletes the upload, version and
    material rows. The body streams through `Vault.sha256()` into 4 MiB pieces, each encrypted into the file
    store (index `piece_count + n`); a matching checksum moves `upload_offset` and `piece_count` in one
    conditional update, a mismatch is `upload.part_checksum_mismatch` (460), a cut body `request.invalid`;
    either way the offset stays and the next attempt overwrites the pieces. `Upload-Offset` other than the
    stored one: `upload.offset_conflict` (409).
  - `POST /api/v1/uploads/{id}/completion` (`{"sha256": "<64 hex>"}`) needs the whole file
    (`upload.incomplete` 409), decrypts the pieces one at a time through `Vault.sha256()`: a match makes the
    version `waiting` with its format and size and the upload `complete` (its pieces stay as the raw upload
    for the first ingestion step) and answers 201 `{materialId, versionId, state}`; a mismatch is
    `upload.file_checksum_mismatch` (422), the version `discarded` and the pieces removed.
  - An open upload past its expiry answers `upload.expired` (410). `materials.service.UploadSweep` (every
    `GMSHIELD_UPLOAD_SWEEP_EVERY`, 10 min) removes it in three resumable steps: as the owner, the version
    `discarded` and the upload `removing`; then `FileStore.deleteFile`; then the row. It finishes every
    `removing` row first, and runs on a connection of its own.
  - `material` and `material_version` (`V6__materials.sql`) are library tables (`@TenantId`, JPA only);
    `upload` is a working table through `JdbcClient` with no content, no name and no hash. Another owner's
    id, an unknown id and a malformed id all answer `request.not_found` (404).
- **The guided upload's details and the materials list (`materials`, Story 1.14).** `Upload-Metadata` at creation
  may also carry `kind` (`rulebook`, `supplement`, `homebrew`, `campaign_notes`, `other`), `language` (`pt-BR`,
  `en`, or `detect`, stored as null for a later read stage), and `campaign` or `system` (the id of one of the
  owner's, checked through `library.service.Library`). All are optional (an older frontend sends none). A bad
  value, an unknown or another owner's id, or both `campaign` and `system` answer `upload.metadata_invalid` (400),
  which never says which, and nothing is stored. Other keys are still ignored.
  - `V7__material_details.sql`: `material.kind` and `material.language` (nullable, each with a CHECK of every
    value) and `material.campaign_id` (nullable, FK `(owner_id, campaign_id)` to `campaign`); the library table
    `material_system (id, owner_id, material_id, system_id)`, unique per `(owner_id, material_id, system_id)`, with
    owner-scoped FKs to `material` (on delete cascade, so a previous release that removes a refused upload's
    material is not stopped) and `system`. One row per material in this release; several systems come with
    Epic 3. Everything is written in creation's transaction; a refused format removes the row with the material.
  - `GET /api/v1/materials?state=…&limit=&cursor=`: the owner's materials whose newest version (latest
    `created_at`, then greatest id) is in one of the given states, newest material first (descending id),
    `{items, nextCursor}`. `state` is repeatable and required, `limit` 1 to 50 (default 20), `cursor` the previous
    `nextCursor`; anything else is `request.invalid` (400). JPQL only (`JpaMaterialStore.newestIn`).
  - `GET /api/v1/materials/{id}`: one material. Another owner's id, an unknown id and a malformed id all answer
    `request.not_found` (404).
  - Each item is `{id, name, state, kind?, upload?}`; `upload` (`{id, offset, length, expiresAt}`, from the open
    `upload` row by its `(owner_id, version_id)` index) is there only while the newest version is `uploading`.
    Every answer is `no-store`. Nothing logs a name, a kind or where a material belongs.
- **Cleaning an upload and splitting it into pages (`materials` and `jobs`, Story 1.15, AD-17).** Completing a PDF
  upload creates, in the same transaction, the version's first ingestion step: a job of kind `ingestion` (ingestion
  lane) and an `ingestion_step` row (stage `clean`, the raw upload as its input). Other formats stay `waiting` until
  their own stories. `materials.service.IngestionSteps` is the kind's `JobHandler`:
  - its `input()` (the claim's transaction) moves a `waiting` version to `processing` and gives the worker the
    stage, the format, the page range and the input's name (`source`);
  - its `openInput()` streams the step's input file (the raw upload, or one of the version's files) piece by piece;
  - `completed()` records one step report, which it never interprets beyond recording: `pipelineVersion` (the first
    report's is kept; another fails the version with `ingestion.pipeline_changed`), `pageTotal`, `original` and
    `batches` (adopted from the job's outputs into `version_file`), and `next` steps (new `ingestion` jobs). A
    report whose next steps would take the version past `GMSHIELD_INGESTION_STEPS_PER_PAGE` x pages + 1 steps fails
    it with `ingestion.too_many_steps`. Once an original is recorded the raw upload is marked `removing` in the same
    transaction; its pieces and row go after the commit (the upload sweep finishes what is left). A result that
    cannot be recorded answers `job.result_invalid` (400) and changes nothing;
  - `failed()` fails the version with the code (`failure_code`; a code outside `ingestion.` is recorded as
    `ingestion.step_failed`); for `ingestion.password_required` the raw upload and every version file go too, and
    the material stays so the GM sees why.
  - Generic job files (internal contract 1.2.0, `jobs.service.JobFiles`): `GET
    /internal/v1/jobs/{jobId}/claims/{claimId}/inputs/{name}` streams what the handler opens; `POST .../outputs`
    streams a body into the file store in 4 MiB pieces sealed under a backend-minted id and answers `{fileId, size,
    sha256}`. Both need the live claim's grant (`job.grant_refused`, 403), checked again before every piece and,
    with the job's row locked, when the output is recorded; an output is capped at `GMSHIELD_JOB_OUTPUT_MAX_BYTES`
    and a claim's outputs at `GMSHIELD_JOB_OUTPUT_CLAIM_MAX_BYTES` (`job.output_too_large`, 413). A handler adopts an
    output with `Jobs.adoptOutput`; the jobs sweep removes every `job_output` no `done` report adopted once its job
    ended. A failed outcome may say `retry: false`, which ends the job at once.
  - `V8__job_output.sql` (`job_output`, not a library table) and `V9__ingestion.sql` (`material_version.page_total`,
    `pipeline_version`, `failure_code`; the library tables `ingestion_step` and `version_file`, owner-scoped as in
    V4 and V7). The public read (contract 1.6.0) adds `pages: {done, total}` once the total is known (`done` is 0
    until Story 1.16) and `failure` (the code) when the state is `failed`.
- **Reading pages (`materials` and `jobs`, Story 1.16, internal contract 1.3.0).** A completed TXT or Markdown upload
  gets one step too (stage `text`); images still stay `waiting`. The claim input adds `attempt` and, when the step
  reads a file of the version, `inputFileId`, `inputFirstPage` and `inputLastPage`; it carries the chain's
  `pipelineVersion` from the second step on. A report may add, all optional and every range inclusive,
  `layers: [{firstPage, lastPage, layer: text|image}]`, `pages: [{firstPage, lastPage, outcome:
  read_directly|read_by_ocr|unreadable}]` and `texts: [{fileId, firstPage, lastPage}]` (each adopted as a
  `version_file` with role `text`). It is refused with `job.result_invalid` when a range is outside 1 to the page
  total, a page gets a layer or an outcome a second time (in this report or an earlier one; checked in JPQL with the
  version locked), or a `texts` range covers a page without an outcome other than `unreadable`.
  - Each `ingestion_step` gets `ended_at` when its job is completed, finally fails or is cancelled. A report that
    leaves no step unended while every page has an outcome makes the version `needs_review` in the same
    transaction. `pages.done` is the number of pages with an outcome.
  - `ingestion.pipeline_changed` (a failed step, or a report under another pipeline version) restarts the chain in
    one transaction: the version's other unended step jobs are cancelled (`Jobs.cancel`, which also ends a running
    claim), its layers, outcomes, `batch` and `text` files go, `pipeline_version` is cleared (`page_total` stays),
    and a new first step (`clean`, or `text` for a text upload) reads the stored original; the next report's original
    replaces it. A final failure with another code fails the version and cancels its other steps.
  - `V10__page_ranges.sql`: the library tables `page_layer` and `page_outcome` (owner, id, version, first, last,
    value), owner-scoped as in V9; `ingestion_step.ended_at`; `version_file.role` gains `text`.
- **Passages (`materials`, Story 1.18, internal contract 1.4.0).** A `next` item whose `input` is the word `texts`
  (not a file id) is a step over every text file of the version: it is recorded **held**, an `ingestion_step` with no
  job (`job_id` null, `input_texts` true, no `input_file_id`). The report that leaves every page with an outcome and
  only held steps unended creates their jobs in its transaction; with nothing unended the version becomes
  `needs_review` as before. A failure or a restart ends held steps as well as cancelling jobs; a restart also removes
  the version's passages and outline. Held and `texts` steps never count toward `pages.reading`. The backend never
  reads the stage name (the worker's `passages`).
  - The claim input of a `texts` step has `input: "texts"` and `texts: [{name: "text-<fileId>", firstPage,
    lastPage}]`, the version's text files in page order; `GET …/inputs/text-<fileId>` streams one of them to that step
    only (404 for anything else, `source` included).
  - A report may add `passages: [{page, sectionPath, text, boxes | range}]` in reading order and `outline: [{level,
    title, page}]`. It is refused with `job.result_invalid` (detail codes `ingestion.passage_invalid`,
    `ingestion.outline_invalid`, or `ingestion.pages_invalid` for a page) when a page is outside 1 to the page total,
    a text has not 1 to 16,384 code points, `sectionPath` is not an array (it is required, possibly empty) or is
    deeper than 32, a title (in a path or the outline) has not 1 to 1,000 code points, an outline level is outside 0
    to 1,000, or a passage has both or neither of `boxes` (at most 16,384, each `[left, top, right, bottom]`
    fractions) and `range` (`[start, end)` integer code points, `0 <= start < end`). A report carrying either replaces the version's passages and outline in
    its transaction, so a retried report leaves one set. Each passage gets its id (UUID v7) and its ordinal here.
  - `V11__passages.sql`: `ingestion_step.job_id` and `input_file_id` nullable, `input_texts`, and their checks; the
    library tables `passage` (owner, id, version, ordinal, page, `text` and `place` encrypted, each bound to
    `passage`, its field, the row and the owner; `place` is the section path with boxes or range, as JSON) and
    `version_outline` (one encrypted JSON outline per version), owner-scoped as in V10.
  - `Materials.passage(id)` gives the material's id and name, the version id, the section path, the page and the
    text; `Materials.outline(versionId)` the sections (level, title, page); both empty for an unknown id or another
    owner's. No public route reads them yet (Story 1.20).
- **Audit log (`audit`, Story 1.10).** `AuditLog.append(action, accountId, details)` adds one row to
  `audit_entry` (`V3__audit_entry.sql`): id, time, action, account id, trace id and a JSON of details
  that may hold only ids, whole numbers, flags and short lower-case codes, so a token, a hash, an
  email address, a username or text is refused before any write. There is no update, delete or read
  path; the refusal in the database comes with Story 9.1 and the reader with Epic 9.
- **Service credential (Story 1.10).** `shared.web.ServiceCredentialFilter` lets an `/api/v1`
  request through only with exactly one `Authorization: Bearer <GMSHIELD_FRONTEND_CREDENTIAL>`,
  compared in constant time; anything else is `service.unauthenticated` (401) before any controller.
  The actuator, `/internal` and the OpenAPI documents keep their own rules.
- **Worker credential (Story 1.12).** `shared.web.WorkerCredentialFilter` does the same for every
  `/internal/v1` request with `Authorization: Bearer <GMSHIELD_WORKER_CREDENTIAL>`. The one exception is
  `/internal/v1/test-mode/**`, and only in test mode, where those routes exist. The frontend's credential
  opens nothing of `/internal/v1`, and the worker's nothing of `/api/v1`.
- **Jobs (`jobs`, Story 1.12).** Work that survives a restart of the backend, Redis or the worker.
  - A job is a row of `job` (`V5__job.sql`, through `JdbcClient`; not a library table: no `@TenantId`, no
    content, input or result): id, owner, kind, lane (`interactive` | `ingestion`), state (`waiting`,
    `offered`, `running`, `done`, `failed`, `cancelled`), attempt, the trace id of the request that created
    it, when it was offered, the live claim (id, the vault token hash of its grant, renew and lapse times)
    and the outcome code.
  - `Jobs.create(kind, input)` (or with a lane) writes the row in the caller's transaction, for the owner of
    the work (`OwnerContext.require()`); `input` runs in that transaction with the new job, for the caller to
    keep the job's input in its own tables. After the commit the job is marked `offered` and its id is added
    to `gmshield:work:interactive` or `gmshield:work:ingestion` (`XADD <stream> * job <id>`), both in one
    transaction of its own: when Redis fails, the job stays `waiting` for the sweep. A rollback leaves no
    row and no message. Interactive jobs are offered at once; ingestion jobs one at a time (none while one is
    `offered` or `running`), the next being the oldest job of the owner after the one offered last.
  - The worker claims with `POST /internal/v1/jobs/{id}/claims` (201): `claimId`, `attempt`, `renewAt`,
    `lapsesAt`, a random `grant` (32 bytes, Base64url; only `vault.tokenHash("grant", …)` is stored), the
    `kind`, the handler's `input` and the job's `traceparent`. A live claim answers `409 job.claimed`; a job
    that is waiting, done, failed or cancelled `409 job.not_offered`. It renews with `POST …/claims/{claimId}/renewals`
    and reports with `POST …/claims/{claimId}/outcome` (`{outcome: done|failed, code?, result?}`, 204),
    both with `X-Job-Grant`: a wrong, expired or replaced grant, or another job's, is `403 job.grant_refused`.
  - Retries are the backend's alone: a lapsed claim or a `failed` outcome goes back to `waiting` while
    `attempt < 3`, otherwise the job ends `failed` with `job.attempts_exhausted` or the reported code.
  - Feature modules implement `jobs.service.JobHandler` (`kind()`, `lane()`, `input(JobRef)`,
    `completed(JobRef, result)`, `failed(JobRef, code)`); `jobs` calls it inside a transaction of its own
    with the job's owner bound (`OwnerContext.callAs`), together with the job's own change.
  - `jobs.service.JobSweep`, the one `@Scheduled` task (every 10 s): creates the consumer group `workers`
    (`XGROUP CREATE … 0 MKSTREAM`) where it is missing, takes back lapsed claims, offers again jobs offered
    more than 2 minutes ago without a claim (Redis lost the message), offers waiting jobs, and trims each
    stream to the last hour (`XTRIM MINID`). Its own writes run on a connection of their own
    (`DataSourceTransactionManager` over a `DelegatingDataSource`), because a JPA transaction needs an owner.
  - Every backend line about a job carries the trace id of the request that created it; the worker logs
    under it and sends it back in `traceparent`.
  - Test mode only: the `probe` kind and `POST /internal/v1/test-mode/probe-jobs` (`{lane, workMs, failTimes}`,
    owned by a fixed test-mode owner; `GET …/{id}` shows its state), with the table `job_probe` from the
    test-mode migration location. Left out of both OpenAPI documents.

## Settings

Read from the environment and checked before anything starts. A refused start prints one line
that names the settings, never a value, and exits with status 78.
[`.env.example`](.env.example) lists them with their defaults and empty secrets, for a start by hand
(`docker run --env-file .env`); [`dev/.env.example`](dev/.env.example) holds the development setup's overrides.

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
| `GMSHIELD_FRONTEND_CREDENTIAL` | the frontend's service credential (required, secret): 32 to 512 characters of `A-Z a-z 0-9 . _ ~ + / = -`; written by GMshield-pipeline's `deploy/data-credentials.sh` into `backend.env` and `frontend.env` |
| `GMSHIELD_WORKER_CREDENTIAL` | the worker's credential (required, secret): the same characters, different from the frontend's; written by `deploy/data-credentials.sh` into `backend.env` and `worker.env` |
| `GMSHIELD_JOB_CLAIM_LAPSE` | a claim lapses this long after its last renewal (default `90s`) |
| `GMSHIELD_JOB_RENEW_EVERY` | the worker renews a claim this often (default `30s`; less than the lapse) |
| `GMSHIELD_JOB_MAX_ATTEMPTS` | claims per job (default `3`) |
| `GMSHIELD_JOB_REOFFER_AFTER` | an offer nobody claimed is made again after this long (default `2m`; less than an hour) |
| `GMSHIELD_JOB_SWEEP_EVERY` | how often the job sweep runs (default `10s`) |
| `GMSHIELD_UPLOAD_SWEEP_EVERY` | how often unfinished uploads past their 6 hours are discarded (default `10m`) |
| `GMSHIELD_JOB_OUTPUT_MAX_BYTES` | the largest file a worker may send back for a job (default `3GB`, a Spring data size in binary units) |
| `GMSHIELD_JOB_OUTPUT_CLAIM_MAX_BYTES` | all the files one claim may send back together (default `8GB`; at least the output limit) |
| `GMSHIELD_INGESTION_STEPS_PER_PAGE` | a version may have at most this many steps per page, plus one (default `4`, 1 to 100) |
| `GMSHIELD_SESSION_RENEW_AFTER` | when the frontend renews a token (default `15m`; Spring durations such as `20s`, `15m`, `PT15M`) |
| `GMSHIELD_SESSION_OVERLAP` | how long the previous token still serves after a renewal (default `60s`) |
| `GMSHIELD_SESSION_IDLE_TIMEOUT` | a session ends after this long without a call (default `24h`) |
| `GMSHIELD_SESSION_ABSOLUTE_TIMEOUT` | a session ends this long after sign-in (default `7d`) |
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
Test mode's service credential is the fixed, public `gmshield-test-mode-frontend-credential-not-a-secret`,
which the frontend's test mode also uses; a `GMSHIELD_FRONTEND_CREDENTIAL` beside test mode is refused
like any other secret, and outside test mode a missing one is `setting.missing`. Likewise the worker's
credential in test mode is the fixed, public `gmshield-test-mode-worker-credential-not-a-secret`, which the
worker's test mode also uses. In both modes the
session timings must be positive with overlap < renew-after < idle ≤ absolute, else `setting.invalid`
names all four (or only the unreadable ones).

## Build and start

Neither Java nor Gradle needs to be installed: everything builds and runs in Docker.

```sh
docker build --target test .                                               # tests and every build guard
docker build --target test-reports --output type=local,dest=out .          # test results, coverage, classes
docker build --target integration -t gmshield-backend-it .                  # the integration tests (see below)
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

The integration tests (`src/integrationTest`, `./gradlew integrationTest`) run against a real
PostgreSQL and Redis at the test-mode hosts `postgres:5432` and `redis:6379` (`GMSHIELD_MODE=test`):
the cross-owner tests of every kind of library item, the starter library's rollback and the owner of
every query, and the jobs (create and rollback, offer, claims, grants, lapses and attempts, a deleted message,
a flushed Redis, trimming and the ingestion round robin), and the uploads (the stored rows, a multi-part upload
read back and decrypted, the lease, completion, a refused format and the sweep resumed after each step), and the
material details (V7's columns and checks, `material_system` within its owner and its cross-owner test, the newest-version
query and its cursor). `check` compiles them but does not run them. To run the image by hand:

```sh
docker network create gm-it
docker run -d --name gm-it-pg --network gm-it --network-alias postgres -e POSTGRES_DB=gmshield \
  -e POSTGRES_USER=gmshield -e POSTGRES_PASSWORD=gmshield-test-mode postgres:18.6-alpine
docker run -d --name gm-it-redis --network gm-it --network-alias redis redis:8.10.3-alpine
docker run --rm --network gm-it -e GMSHIELD_MODE=test gmshield-backend-it   # exit 0: passed
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
account `backend` (keys `gmshield:*` only), the two vault keys, the frontend's service credential
(the same value as in `frontend.env`), the worker's credential (the same value as in `worker.env`;
Dalton reruns `data-credentials.sh` at the first release that carries Story 1.12), and the Google OAuth client id and secret, which Dalton enters by hand (the script writes empty placeholders and never replaces a value;
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
[`sonar-project.properties`](sonar-project.properties)), `integration` runs the integration tests
(`[test]`: the pipeline's integration stage runs it on `develop` beside a throwaway PostgreSQL and
Redis), and the production image is the last stage.
