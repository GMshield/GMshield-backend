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
| Apache PDFBox | 3.0.8 (Apache-2.0; Story 5.7, checked 2026-11-27), with Noto Sans Regular and Bold (SIL OFL 1.1, `src/main/resources/fonts/OFL.txt`) | `build.gradle.kts`, `src/main/resources/fonts/` |
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
Twelve modules exist so far: `shared` (the error model, trace ids, log lines, start-up checks, the
contract versions, the owner context and `Ids`, which mints every id as a UUID v7), `vault` (encryption at rest,
below), `audit` (the append-only audit log, below; it may use `shared`), `jobs` (background work that
survives a restart, below; it may use `shared` and `vault`, and no feature module), `library` (systems,
campaigns and, from Story 6.1, their Shields, below; it may use `shared` and `vault`), `materials` (materials and the resumable upload of their
files, below; it may use `shared`, `vault`, `jobs` and `library`), `scope` (what a campaign may use, below; it may
use `shared`, `library`, `materials` and `vault`), `limits` (the model setting and the usage of model calls, below; it may use `shared` and
`jobs`), `conversations` (a campaign's conversation and its answers, Story 2.4, below; it may use `shared`, `jobs`,
`scope`, `library` and `vault`), `knowledge` (each system's ontology, Story 4.1, and the extracted records, Story 4.2, below; it may
use `shared`, `vault`, `library` and, from Story 4.2, `materials`, and neither `library` nor `materials` may use it), `artifacts` (what the GM writes and keeps in a campaign, Story 5.1, below; it may use `shared`, `vault`, `library` and `knowledge`) and `accounts` (sign-in with Google, accounts and sessions, below; it may use `shared`, `vault`, `audit` and
`library`). `ModuleBoundaryTest` also fails the build when a class outside
`materials` and `scope` depends on `materials.service.ScopeReads` (AD-9, Story 2.2).

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
    it answers 204 whether or not the session existed. `GET /api/v1/me` answers `Me{id, username}`: the
    browser keys this account's pending rolls with the id (Story 6.4 part B, contract 1.60.0).
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
  JPA entities and JPQL (also by id), never native SQL or `JdbcClient` (one exception from Story 6.4: a stored roll's
  insert, `on conflict do nothing` on the owner's key, which names the row's owner; every read stays JPQL).
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
  - `GET /api/v1/campaigns/current` (the owner's current campaign, with its system: Story 3.2, below), `GET
    /api/v1/campaigns/{id}` and `GET /api/v1/systems/{id}`: another owner's id, an unknown id and a
    malformed id all answer the same `request.not_found` (404). Every answer is `no-store`.
  - **The cross-owner audit (Story 3.13 part C).** `CrossOwnerAuditFlowTest` (unit flow, in-memory stores, part of
    `./gradlew check`) gives account A one item of every library kind (system, campaign and its scope settings, an
    ingested and accepted material with a superseded version, pages and pictures, an image set and its open uploads,
    conversations with a bookmarked, a dismissed and a waiting turn and a reference, a search). Account B then calls
    every public route that names an item by id with A's ids: each answer is `request.not_found` (404) with the same
    body as a random id (the trace id aside), also with B's own campaign or material beside A's version. Each body or
    header field that names an item (`campaignId`, `systemId`, `conversationId`, a search's `materialId`,
    `materialsOff`, a material's `belonging`, upload metadata and image-set `campaign`, `system`, `systems` and
    `material`, the `?system=` filters) answers exactly what a random id gets. Afterwards no compared row, job or
    offered message has changed, A reads every item back as it was, and B's lists hold none of A's ids.
    `CrossOwnerRouteGuardTest` reads `/v3/api-docs/public`: an operation whose path has a `{…}` parameter that is
    neither a row of the audit nor in its exemption list (with a reason) fails, naming its method and path, and so
    does a path outside `LibraryOwnerFilter.LIBRARY_ROUTES` that is not in its short list of session and sign-in
    paths; each covered operation is also refused with `session.required` without a session. A new route therefore
    ships with its row in the audit. The audit runs twice: on the in-memory twins of the stores in `./gradlew check`,
    and on PostgreSQL and Redis in `CrossOwnerAuditIntegrationTest` (`./gradlew integrationTest`), which shares the
    unit test's routes, probes and worlds through the test classes on the integration classpath. There, "nothing
    changed" is a dump of A's and B's rows in the tables with an `owner_id` column (a new one is covered without
    editing the test; `account` and `audit_entry` have no such column and are not dumped) and each lane's whole work
    stream (`XRANGE`), compared before and after; a test changes one of A's rows through JDBC between two dumps to
    prove the comparison names it. A store that drops its `@TenantId` filter fails the audit; a JDBC store's
    `owner_id = :owner` filter is proved only where an audited route reaches that store before a `@TenantId` lookup
    refuses the id, which is not the case for `JdbcDeletionStore`'s reads. In memory, the compared tables are a
    hand-kept list in `InMemoryCrossOwnerAuditStores`.
  - **Systems (Story 3.1 part A, public contract 1.17.0, migration V21).** The GM lists, creates and edits systems
    (deleting one is part B, below). A system is `{id, name, edition, description, version}`; `edition` and `description`
    are null when none was given.
    - Fields, counted in code points after trimming: name 1 to 120 (`request.system_name_invalid`, 400, params `min`
      and `max`), edition at most 60 (`request.system_edition_invalid`, params `max`), description at most 2,000
      (`request.system_description_invalid`, params `max`); a blank edition or description is none. Each is stored
      only encrypted, under its own field: `Binding("system", "name"|"edition"|"description", id, owner)`. Logs carry
      ids and the version only (`library.system_created`, `library.system_changed`).
    - Two systems of one owner are the same when name and edition match (Dalton, 2026-11-20): `name_fingerprint` is
      the fingerprint of the name's unique form alone when there is no edition (what every starter system already
      stores, so no row is rewritten), else of `uniqueForm(name) + U+0000 + uniqueForm(edition)`, recomputed on every
      edit. "D&D" 5.2.1 and "D&D" 2024 are two systems; "D&D" with no edition twice is not. A duplicate is
      `request.system_name_taken` (409): the service checks the fingerprint first, and a write that the key
      `system_owner_name_fingerprint_key` refuses anyway is the same 409 (the store turns it into a
      `LibraryConflict`, never the database's message).
    - `GET /api/v1/systems?cursor&limit`: `items` and `nextCursor`, newest first by id (encrypted names cannot be
      sorted in SQL), `limit` 1 to 50 (default 20); a bad limit or cursor is `request.invalid` (400). `POST
      /api/v1/systems` `{name, edition?, description?}` answers 201 with the system. `GET /api/v1/systems/{id}` answers
      every field.
    - `PATCH /api/v1/systems/{id}` `{version, name?, edition?, description?}`: an absent or null field is unchanged,
      a blank edition or description removes it. `version` is the row's (`system.version`, Hibernate `@Version`,
      starting at 0): one that is not the row's is `request.stale_version` (409) and changes nothing, as is an update
      that a concurrent edit beats at the database. It answers the system with its version raised by one. A missing or
      negative version is `request.invalid`.
    - `GET /api/v1/campaigns?system=&cursor&limit`: the system's campaigns, newest first (from Story 3.2 `system` is
      optional, below). `GET /api/v1/materials?state=...&system=` (`materials`) keeps only the materials
      attached to the system (`material_system`) or whose campaign is one of its campaigns
      (`Library.campaignIdsOf`). An unknown, foreign or malformed id answers `request.not_found` (404) on GET, PATCH
      and in `?system=` alike.
    - `V21__system_details.sql` adds `system.edition bytea`, `system.description bytea` and `system.version bigint not
      null default 0`. Additive: the previous release neither reads nor writes them, so rolling back is safe. What
      users see after a rollback: two editions of one game show as two systems with the same name, descriptions are
      not shown. (The old release has no route that creates a system, so it cannot add a same-name system without an
      edition; only a new account's starter system is created there.)
    - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.17.0). The worker does
      not change.
  - **Delete a system (Story 3.1 part B, public contract 1.18.0, migration V22, AD-22).** A recorded process that
    resumes after a restart (`library.service.SystemDeletion`). What goes: the system, its campaigns, their
    conversations, and every material attached to one of its campaigns or to this system and no other. A material also
    attached to another system stays and only loses this attachment. Deleting the system that holds the GM's only
    campaign is allowed: there is then no current campaign (`GET /api/v1/campaigns/current` is 404). No undo.
    - `GET /api/v1/systems/{id}/deletion` answers `{campaigns, conversations, materials}`, what the confirmation
      dialog names; each module counts its own part (materials: those that go whose newest version is not discarded).
    - `DELETE /api/v1/systems/{id}` answers 202 with no body. In one transaction: `system.state` becomes `deleting`
      (and `name_fingerprint` becomes the system's id, so the name and edition are free at once); each module's
      `SystemDeletionHandler.prepare` cancels the jobs of what goes (ingestion steps and index changes of its
      materials' versions, the `question.answer` jobs of its campaigns' turns), after locking them and reading when
      their claims lapse (`Jobs.claimLapse`), and every version of a material that goes becomes `discarded`, so it
      leaves every list and scope at once; a `deletion` row records owner, kind `system`, target and `not_before`
      (the latest claim lapse, or now).
    - From that commit the system and its campaigns are `request.not_found` everywhere: the system list, GET, PATCH,
      DELETE and `/deletion`, `?system=`, `GET /api/v1/campaigns/{id}`, the current campaign and every question and
      conversation read that goes through it. Another owner's system is the same 404 on both routes.
    - Removal runs at `not_before` (an index change already running may write points until its claim lapses, at most
      `GMSHIELD_JOB_CLAIM_LAPSE` after its last renewal): a one-shot pass scheduled after the commit, and
      `SystemDeletion.sweep` at the start and every `GMSHIELD_SYSTEM_DELETION_SWEEP_EVERY` (10 min) for whatever is
      due, each pass as the owner (`OwnerContext.callAs`). A pass collects what goes again and prepares it again (an
      upload that completed during the wait, a question asked as the system went); when that cancels a running job,
      `not_before` moves and the pass stops. Then `materials` starts one `index.removal` per version unless it has one
      (the version locked, so never two), runs the discard's `VersionRemoval`, removes the pieces of its files and
      uploads, then every row of the version (page views, passages, outline, page ranges, `version_file`, `upload`,
      `ingestion_step`, `index_change`) and the version, then the material with its `material_system` rows; and
      detaches the materials that stay. `conversations` deletes each conversation's turns with their references and
      event batches, then the conversation (`model_call_usage` stays: it names jobs only). Last, one transaction
      deletes the campaigns and the system and sets `deletion.done_at`. Every step is safe to repeat; a pass that
      fails (logged `library.deletion_deferred` with its error type) leaves the row for the next one.
    - `deletion` is not a library table (AD-7): ids and times only, read and written with `JdbcClient` (joined to the
      owner's transaction), without `@TenantId`; the sweep's list alone reads across owners. The handler interface
      lives in `library.service`, so `library` still depends on `shared` and `vault` only.
    - `V22__system_deletion.sql` adds `system.state text not null default 'ready'` (check `ready|deleting`) and the
      `deletion` table. Additive: the previous release neither reads nor writes them. Rolling back while a deletion
      is pending shows the system again, with whatever its removal has not removed yet, under its id as fingerprint
      (its name can then be taken twice). Rolling forward again finishes every deletion left pending (system `deleting`,
      `deletion.done_at` null) at the first sweep, with whatever the GM added to that system meanwhile. To keep such a
      system, cancel its deletion before rolling back: `update deletion set done_at = now() where target_id = '<id>'`
      and `update system set state = 'ready' where id = '<id>'` (its `name_fingerprint` stays the system's id, so the
      same name and edition can be created again).
    - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.18.0). The worker does
      not change: `index.removal` per version already exists.
  - **Campaigns (Story 3.2 part A, public contract 1.19.0, migration V23).** The GM lists, creates, renames, archives,
    restores and chooses campaigns (deleting one is part B). A campaign is `{id, name, system, archived, version}`;
    a list item is `{id, name, system: {id, name, edition}, archived}`.
    - Name: 1 to 120 code points after trimming (`request.campaign_name_invalid`, 400, params `min` and `max`), stored
      only encrypted (`Binding("campaign", "name", id, owner)`). Its system is set at creation and never changes. A new
      campaign starts with the starting scope (`ScopeSettings.STARTING`, which the resolver already reads for every
      campaign; nothing is stored until Story 3.5).
    - **Unique per system** (Dalton, 2026-11-20): `campaign.name_fingerprint` is the fingerprint of the name's unique
      form (as a system's, under `campaign`/`name`), unique on `(owner_id, system_id, name_fingerprint)`
      (`campaign_owner_system_name_fingerprint_key`). A duplicate on POST or PATCH is `request.campaign_name_taken`
      (409); the service checks first and a write the key refuses anyway is the same 409. The same name in another
      system is fine. An archived campaign keeps its name, so its name stays taken until it is renamed or deleted (part B). A first sign-in stores the starter campaign's fingerprint.
    - **Rows from before V23** have no fingerprint (SQL cannot compute a keyed one, and the key ignores nulls). V23
      lists their owners in `campaign_fingerprint_backfill` (owner ids only, not a library table); once the application
      is ready, `library.service.CampaignFingerprints` fills each listed owner's rows as that owner (decrypt, then
      fingerprint, through JPA), then removes the owner (`JdbcClient`). Repeatable: a start with nobody listed does
      nothing, an owner whose fill fails stays for the next start (`library.campaign_fingerprints_failed`), and a row
      whose name another campaign of its system already holds keeps none (`library.campaign_fingerprint_duplicate`).
      Until then a create or a rename also compares the name with the system's campaigns that have no fingerprint.
      Switched off by `gmshield.library.campaign-fingerprints.enabled=false` (the unit tests).
    - `GET /api/v1/campaigns?system=&archived=&cursor&limit`: every campaign of the owner's ready systems, or the
      system's only; `archived=false` (the default) leaves archived ones out, `archived=true` lists only them; newest
      first by id; a bad `archived`, limit or cursor is `request.invalid` (400). An unknown, foreign or malformed
      `system` is `request.not_found`. The system page's campaigns are this list with `system`.
    - `POST /api/v1/campaigns` `{name, systemId}` answers 201 with the campaign, which becomes the current one. An
      unknown, foreign, malformed or deleting system is 404; no `systemId` is `request.invalid`.
    - `PATCH /api/v1/campaigns/{id}` `{version, name?, archived?}`: renames, archives (`archived: true`) or restores
      (`false`) on the row's `version` (Hibernate `@Version`, from 0); a stale one is `request.stale_version` (409) and
      changes nothing, and neither `name` nor `archived` is `request.invalid` (400). Without a name the stored name and
      fingerprint stay as they are (no uniqueness check), so a row from before V23 is archived and restored as it is. Archiving keeps every row and sets `archived_at`: the campaign leaves the lists, the switcher and
      the current position, still opens by id (`GET /api/v1/campaigns/{id}`, its conversation, stored references,
      questions asked on it) and can be restored. Archive and restore are reversible, so the frontend asks no
      confirmation. Logs: `library.campaign_created`, `library.campaign_changed` (ids, version, archived).
    - **Current campaign:** `PUT /api/v1/campaigns/current` `{campaignId}` sets the campaign's `chosen_at` and answers
      it (200); an archived, foreign, unknown or malformed id is 404 `request.not_found`, and nothing changes. It does
      not raise the version (`@OptimisticLock(excluded = true)`), so a switch never makes an open rename stale. The
      current campaign is the ready, not archived campaign with the latest `chosen_at`, else the oldest (created
      first, then the smallest id); none when there is none (`GET .../current` and `GET /api/v1/conversations/current`
      are then 404, which the frontend shows as the composer disabled). Archiving clears `chosen_at`, so archiving or
      deleting the chosen campaign falls back by itself and a restored campaign does not come back as current. Nothing
      about the current campaign is kept in the browser.
    - `GET /api/v1/campaigns/{id}/conversation` (`conversations`) answers `{id, questions}`: the campaign's one
      conversation and its turn count; 404 when it has none or the campaign is not the owner's.
    - A system's deletion still counts and removes its archived campaigns (`campaignIdsOf` and the deletion's own reads
      ignore the archive).
    - `V23__campaign_management.sql` adds `campaign.version bigint not null default 0`, `archived_at`, `chosen_at`,
      `name_fingerprint bytea`, the key, and `campaign_fingerprint_backfill`. Additive: the previous release neither
      reads nor writes them. What users see after a rollback: archived campaigns show again, the current campaign is
      the oldest again, and a starter made meanwhile has no fingerprint (filled at the next roll forward only when its
      owner is listed; otherwise it is still compared by name on every create and rename).
    - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.19.0). The worker does
      not change.
  - **Delete a campaign (Story 3.2 part B, public contract 1.20.0, migration V24, AD-22).** The recorded, resumable
    deletion of a system (above), extended to a `deletion` row of kind `campaign`: the same `SystemDeletion` process,
    sweep and one-shot passes, the row's kind choosing what is collected and what `library` removes last. What goes:
    the campaign, its conversation (turns, event batches, references) and every material attached to it
    (`material.campaign_id`); nothing of its system or of the other campaigns. An archived campaign, the current one
    and the GM's last one can all be deleted; the current campaign then falls back (part A's rule), or there is none
    (the composer disabled). No undo.
    - `GET /api/v1/campaigns/{id}/deletion` answers `{conversations, materials}` (materials whose newest version is
      not discarded), what the confirmation dialog names.
    - `DELETE /api/v1/campaigns/{id}` answers 202 with no body. In one transaction: `campaign.state` becomes
      `deleting` (the row locked first; `name_fingerprint` becomes the campaign's id, so the name is free in its
      system at once); each handler's `prepare` runs with `Going(null, [campaign])` (no system part: `materials` takes
      the campaign's materials alone and detaches nothing; `conversations` cancels the campaign's answer jobs); a
      `deletion` row records owner, kind `campaign`, target and `not_before` (the latest claim lapse, or now). Logs:
      `library.campaign_deleting`, then `library.campaign_deleted` (ids and times only).
    - From that commit the campaign is `request.not_found` to every read and write that goes through `library`: the
      lists (and the switcher), GET, PATCH, DELETE and `/deletion`, `PUT .../current`, the current campaign, its
      conversation, questions asked on it, uploads naming it. A system's preview no longer counts it. Reads by turn id
      (a turn, its stop, events and references) still answer until the removal, as for a system (3.1 B, finding V4).
    - Removal at `not_before`, per owner, as for a system: materials (one `index.removal` per version, never two,
      `VersionRemoval`, files, rows), the conversation, then the campaign row and `deletion.done_at`. Each pass
      collects again, so an upload that lands during the wait goes too.
    - A system deleted while one of its campaigns is deleting takes that campaign too (a system's deletion takes all
      its campaigns, deleting ones included); the campaign's own deletion then finds it gone and is marked done.
    - `V24__campaign_deletion.sql` adds `campaign.state text not null default 'ready'` (check `ready|deleting`) and
      lets `deletion.kind` be `campaign`. Additive: the previous release neither reads nor writes `campaign.state`.
      Rolling back while a campaign deletion is pending shows the campaign again, with whatever its removal has not
      removed yet, under its id as fingerprint; that release's sweep finds no system with the row's target and marks
      the row done, so after rolling forward again the campaign stays hidden (`deleting`, no pending row) with its
      remaining data. To finish it, reopen its row: `update deletion set done_at = null where kind = 'campaign' and
      target_id = '<id>'`. To keep it instead, in one transaction: `delete from deletion where kind = 'campaign' and
      target_id = '<id>'` (else a later delete of it is refused by `deletion_owner_target_key`), `update campaign set
      state = 'ready', name_fingerprint = null where id = '<id>'` (its fingerprint is still its own id, which protects
      no name), and `insert into campaign_fingerprint_backfill (owner_id) select owner_id from campaign where id =
      '<id>' on conflict do nothing`; the next start fills its fingerprint. If another campaign of its system took its
      name meanwhile, rename that one in the app first: otherwise the fill leaves this campaign without a fingerprint
      (`library.campaign_fingerprint_duplicate`) and two campaigns share the name.
    - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.20.0). The worker does
      not change.
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
  - **A replacement (Story 3.4, public contract 1.23.0).** `Upload-Metadata` may name `material` (the id of one of
    the owner's materials) beside `filename` and nothing else: with `kind`, `language`, `campaign`, `system` or
    `systems`, or an empty id, it is `upload.metadata_invalid` (400). It inserts only a `material_version` in
    `uploading` and its upload; the material keeps its name, kind, language and place. `NewVersions.lockReady`
    checks it in the same transaction, with the material locked (`MaterialStore.lockMaterial`, a `PESSIMISTIC_WRITE`
    refresh as a change of where it belongs takes): another owner's, an unknown, a malformed or a deleting material is
    `request.not_found` (404); a version in `uploading`, `waiting`, `processing`, `needs_review` or `failed` is
    `material.version_in_progress` (409); no `ready` version (every one discarded) is `material.not_ready` (409).
    `ImageSetRequest.material` does the same for an image set. The new version goes through ingestion and review; a
    refused format deletes only it, and a mismatch, an expiry or a discard leaves the material `ready` on the old
    one, which serves every scope meanwhile.
  - **The current version (Story 3.4).** A material shows its current version: its newest version (latest
    `created_at`, then greatest id) that is not `discarded`, or its newest one when every version is discarded
    (`MaterialStore.current`, `currentIn`, `currentAttached`; one JPQL rule). It decides the material's state, size,
    report, actions, review page and the list's `state` filter. `Material.serving` (`{pages, size}`) is the
    material's `ready` version while that is not the current one, else null.
  - Deploy order for public contract 1.23.0 (no migration): the backend first, then the frontend (its health check
    fails against a 1.22.0 backend). The worker does not change. **A backend rollback below 1.23.0 is not safe once a
    replacement exists**: a 1.22 backend reads a material's newest version as its state, so a discarded or expired
    replacement makes a serving material read `discarded` (and offer no action), and its refused-format path deletes
    the whole material, the serving version included. Nothing guards against it in code: before rolling back, check
    that no material has more than one version (`select material_id from material_version group by material_id
    having count(*) > 1`), or accept that loss.
  - **Delete a material, remove a superseded version (Story 3.4 part B, public contract 1.24.0, migration V26,
    AD-22).** The recorded, resumable deletion of a system (above), extended to `deletion` rows of kinds `material`
    and `version`: the same `SystemDeletion` process, sweep, one-shot passes, claim wait and preparing again.
    `library` knows neither: `materials.service.MaterialDeletions` checks the target, then `SystemDeletion
    .deleteMaterial` or `.removeVersion` writes the row (once per owner, kind and target: `insert ... on conflict do
    nothing`, so a second delete changes nothing) in one transaction with every handler's `prepare`, given
    `Going.ofMaterial(id)` or `Going.ofVersion(id)`. There is no system or campaign to mark or delete: a pass is done
    once the handlers' removal ends. `conversations` takes nothing (references are stored by value). No undo.
    - `GET /api/v1/materials/{id}/deletion` answers `{versions, pages}`: the material's versions that are not
      `discarded`, and the sum of their page totals.
    - `DELETE /api/v1/materials/{id}` answers 202 with no body, and again for a material already being deleted.
      `SystemMaterialsDeletion.prepare` discards every version of it (steps and index changes cancelled, the latest
      claim lapse kept as `not_before`), so it leaves every list, count and scope at once and references to it read
      `removed`. The removal runs `removeVersion` for each version (one `index.removal` per version, never two,
      `VersionRemoval`, files, rows), then deletes the material with its attachments. A version that is no longer
      `discarded` (an upload completed during the wait) fails the pass, and the next one prepares again.
    - `DELETE /api/v1/materials/{id}/versions/{versionId}` answers 202 for a `superseded` version of that material,
      and again once its removal is recorded; any other state is `material.version_not_removable` (409): a ready or
      in-progress version is discarded instead. The same prepare and removal, for that version alone; the material
      and its other versions stay. Its row goes, so a reference to it reads `removed` (`ScopeReads.removed`) and its
      page is not found.
    - All three answer `request.not_found` (404) for another owner's material or version, an unknown or malformed id,
      a version of another material, or a material whose every place is being deleted.
    - `Material.superseded` (`[{id, createdAt, pages, size}]`, newest first, one read for a whole page): the
      material's `superseded` versions. One whose removal is recorded is `discarded` already, so it is left out.
    - Failed removals: `IndexRemovalRetrySweep` (at the start and every `GMSHIELD_INDEX_RETRY_EVERY`, 10 min; off
      with `gmshield.materials.index-retry-sweep.enabled=false`, the index retry sweep's own switch, so that setting
      stops the retries of failed index changes too) visits each owner with a failed `index.removal` job
      and gives each failed job that still has its `index_removal` row a new job with the same input (owner, source);
      the row moves to it (`IndexStore.moveRemoval`), so the failed job, kept as history, is never retried twice. From
      Story 4.5 part B it does the same for failed `index.record_removal` jobs and their `record_removal` rows
      (`IndexStore.moveRecordRemoval`), each kind in its own pass. Logs: `index.removal_retried`,
      `index.record_removal_retried`.
    - Logs: `library.material_deleting`/`library.version_deleting`, then `library.material_deleted`/
      `library.version_deleted`; `material.delete`, `material.version_remove` (ids and times only).
    - `V26__material_deletion.sql` lets `deletion.kind` be `material` or `version`. Additive. Rolling back while one
      is pending: that release's sweep finds no system with the row's target and marks it done; the versions the
      delete discarded stay discarded (their content is removed by that release's version removal sweep, their rows
      and points are not). For kind `material` the material row can also be left with no version (a pass that
      stopped after its last version went, before the material did). After rolling forward, list the targets whose
      row was marked done although something of them is left:
      `select kind, target_id from deletion where done_at is not null and ((kind = 'material' and target_id in
      (select id from material)) or (kind = 'version' and target_id in (select id from material_version)))`;
      then reopen each (`update deletion set done_at = null where kind = '<kind>' and target_id = '<id>'`): the next
      pass removes what is left, the versions, their points and, for `material`, the material row.
    - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.24.0). The worker does
      not change. The first start after the deploy runs the index removal retry sweep at once, for every owner: it
      starts again every failed `index.removal` job left since Stories 1.20 (a discard) and 3.1 part B (a system's or
      a campaign's deletion), one new job each on the ingestion lane.
- **The guided upload's details and the materials list (`materials`, Story 1.14).** `Upload-Metadata` at creation
  may also carry `kind` (`rulebook`, `supplement`, `homebrew`, `campaign_notes`, `other`), `language` (`pt-BR`,
  `en`, or `detect`, stored as null for a later read stage), and `campaign` or `system` (the id of one of the
  owner's, checked through `library.service.Library`); from Story 3.3 `systems` (below) names several systems. All
  are optional (an older frontend sends none). A bad value, an unknown or another owner's id, or both `campaign` and
  `system` answer `upload.metadata_invalid` (400), which never says which, and nothing is stored. Other keys are
  still ignored.
  - `V7__material_details.sql`: `material.kind` and `material.language` (nullable, each with a CHECK of every
    value) and `material.campaign_id` (nullable, FK `(owner_id, campaign_id)` to `campaign`); the library table
    `material_system (id, owner_id, material_id, system_id)`, unique per `(owner_id, material_id, system_id)`, with
    owner-scoped FKs to `material` (on delete cascade, so a previous release that removes a refused upload's
    material is not stopped) and `system`. One row per system the material belongs to (several from Story 3.3).
    Everything is written in creation's transaction; a refused format removes the row with the material.
  - `GET /api/v1/materials?state=…&limit=&cursor=`: the owner's materials whose newest version (latest
    `created_at`, then greatest id) is in one of the given states, newest material first (descending id),
    `{items, nextCursor}`. `state` is repeatable and required, `limit` 1 to 50 (default 20), `cursor` the previous
    `nextCursor`; anything else is `request.invalid` (400). JPQL only (`JpaMaterialStore.newestIn`, `currentIn` from Story 3.4). From Story 3.3
    `state` is optional: without it, every material.
  - `GET /api/v1/materials/{id}`: one material. Another owner's id, an unknown id and a malformed id all answer
    `request.not_found` (404).
  - Each item is `{id, name, state, kind?, upload?}`; `upload` (`{id, offset, length, expiresAt}`, from the open
    `upload` row by its `(owner_id, version_id)` index) is there only while the newest version is `uploading`.
    Every answer is `no-store`. Nothing logs a name, a kind or where a material belongs.
- **Where a material belongs (`materials`, Story 3.3, public contract 1.21.0, migration V25).** A material belongs
  to exactly one of these: one campaign (`material.campaign_id`), or one to 100 systems (`material_system` rows). The
  database cannot check that rule across two tables, so its one writer does: `Uploads.place` (the upload's check) and
  `MaterialBelongings` (the change), both refusing a campaign with systems, more than 100 systems, or a target that is
  not one of the owner's ready campaigns (archived ones included) and systems. A material uploaded with no place
  (`none`) can be given one; no request takes a material back to no place.
  - Uploads: tus metadata key `systems` (one to 100 ids, comma-separated inside the Base64 value) and
    `ImageSetRequest.systems` (an array of one to 100; an empty one is refused). At most 100 ids as sent; a repeated
    id is stored once. The single `system` key still names one; `system` with `systems`, or `campaign` with either, is
    `upload.metadata_invalid`.
  - `PATCH /api/v1/materials/{id}` `{version, belonging: {campaign} | {systems: [..]}}` answers 200 with the material,
    its version raised by one. `material.version` (V25, `bigint not null default 0`, Hibernate `@Version`) is named by
    the change; a stale one is `request.stale_version` (409). Both fields, neither, an empty list or one of more than
    100 ids as sent (a repeated id is stored once), or an unknown, malformed, another owner's or deleting target is
    `material.belonging_invalid` (400), which never says which; no version or a malformed body is `request.invalid`
    (400); another owner's material, an unknown or malformed id, and a material whose every place is being deleted are
    `request.not_found` (404). The material is locked (`select ... for update`) before its version is compared, so
    two changes take turns and the second is stale. Systems change by difference: rows that stay are kept, the others
    are deleted, new ones inserted (never a delete and insert of one key). The row's `updated_at` always moves, so the
    version rises even when only its systems changed.
  - A change writes only `material.campaign_id`, `material_system` rows and `material.version`: never a version, a
    job, a passage or a Qdrant point. Every scope read resolves again (`AnswerJobs` resolves on each passages fetch),
    so the change applies from the next question, and a question running while its material moves out gets
    `scope.passage_refused` at its next fetch (`ConversationFlowTest`). References stored with a turn still open.
  - The material view gains `version`, `size` (the newest version's bytes, absent until known) and `belonging:
    {campaign: {id, name, system: {id, name}} | null, systems: [{id, name}]}`: only ready places, names decrypted.
    The places of a page come from one read of its `material_system` rows and one `Library.places` call (one read
    of the campaigns and one of the systems), never one read per row. A list leaves out a material that belongs
    somewhere but whose every place is being deleted (a page may then hold fewer than the limit); `GET` by id still
    answers it with an empty belonging.
  - `V25__material_belonging.sql` adds `material.version`. Additive: the previous release neither reads nor writes
    it (its inserts take the default). After a rollback, materials keep the place they were given last; a material
    with several systems shows (and is resolved) in each of them, as the previous release's queries read every
    `material_system` row.
  - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.21.0). The worker does
    not change.
- **Numbered pages (`library`, `materials`, public contract 1.22.0).** The three lists (`GET /api/v1/materials`,
  `/api/v1/systems`, `/api/v1/campaigns`) name `total`, how many items the list has in all for the same filters
  (`state` and `system`; `system` and `archived`), and take `offset` (0 to 100000, default 0): how many of the newest to
  skip, so a client shows page 3 with one call instead of reading two pages by cursor first.
  - A page starts after a `cursor` or at an `offset`, never both: an `offset` above 0 with a `cursor`, or one outside
    its range, is `request.invalid` (400). An offset past the end is an empty page that still names the total.
  - The total is a `count` of the same query the list reads, in constant JPQL texts (`JpaLibraryStore.SYSTEM_COUNT` and
    the four campaign counts; `JpaMaterialStore.NEWEST_COUNT` and the two attached counts). The materials total counts
    a material whose every place is being deleted, which a page leaves out, until that deletion is done.
  - No migration, and the worker does not change. Deploy order: the backend first, then a frontend whose minimum public
    contract is 1.22.0. A frontend on 1.21.0 keeps working: it ignores `total` and never sends `offset`.
- **Cleaning an upload and splitting it into pages (`materials` and `jobs`, Story 1.15, AD-17).** Completing a PDF
  upload creates, in the same transaction, the version's first ingestion step: a job of kind `ingestion` (ingestion
  lane) and an `ingestion_step` row (stage `clean`, the raw upload as its input). Text got its stage with Story 1.16
  and images with Story 1.21. `materials.service.IngestionSteps` is the kind's `JobHandler`:
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
  gets one step too (stage `text`); images got theirs with Story 1.21. The claim input adds `attempt` and, when the step
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
    only (404 for anything else, `source` included). When a PDF version has a cleaned original, the claim input also
    has `original: "original"`, and `GET …/inputs/original` streams it to that step; the worker reads its
    bookmarks for the section paths (pipeline "7"), and an older worker ignores it. A text upload's or an image
    set's step gets neither the key nor the file (404). The internal contract does not
    change: the claim input is a free-form object.
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
    owner's. From Story 1.20 `passage(id)` answers only passages of a `ready` version; no public route reads a
    passage, and the outline reaches the GM through the quality report.
- **Page pictures with a text layer (`materials`, Story 1.19, internal contract 1.5.0, public contract 1.8.0).** A
  reading report may add `pageViews`: for a PDF page `{page, picture: {fileId, width, height}, lines: [{text,
  box}]}`, for a text page `{page, text}`. It is refused with `job.result_invalid` (detail code
  `ingestion.page_view_invalid`) unless each page is one this report gives an outcome and appears once, the picture
  is an output of this job (adopted here as a `version_file` with role `picture`, `first_page = last_page = page`),
  width and height are 1 to 10,000, there are at most 10,000 lines, each of 1 to 4,096 code points with a box of
  four fractions (`[left, top, right, bottom]` of the picture from its top-left corner), and the text has at most
  16,384 code points. A report replaces the views of the pages it carries (their earlier pictures are removed after
  the commit), so a retry leaves one view per page; a restart removes the page views and the pictures with the
  passages. The backend never reads a picture: it stores and serves the worker's bytes. Deploy this release before a
  worker of pipeline "5" (its floor is internal contract 1.5.0).
  - `V12__page_views.sql`: `version_file.role` gains `picture`; the library table `page_view` (owner, id, version,
    page, `picture_file_id` with `width` and `height`, all three null for a text page, and `content`, the lines or
    the text as JSON encrypted under `page_view`, `content`, the row and the owner), unique on (owner, version,
    page), its picture a file of the same owner; an index on `passage (owner_id, version_id, page)`.
  - `GET /api/v1/materials/{id}/pages/{page}?ground=review` answers `{page, pageTotal, picture: {width, height} |
    null, lines, text | null, passages: [{id, boxes | range}]}` (the page's passages in reading order, through the
    new index), and `GET …/pages/{page}/picture?ground=review` streams the WebP piece by piece (`image/webp`,
    `nosniff`); both `no-store`. The ground `review` (the owner reviewing a version) serves the material's current
    version only while it is `needs_review` or `ready`. Another owner's material, an unknown or malformed id, a
    version in any other state, a page outside 1 to the page total, a page with no view, and a picture of a page
    with none are the same `request.not_found` (404); a missing or other ground is `page.ground_unknown` (400).
    Grounds are a parameter (`Materials.Ground`) so that Epics 2 and 3 add theirs without a new path.
- **The quality report and the GM's review (`materials`, Story 1.20, public contract 1.9.0).** Each `Material` view
  gains `actions`: those of `accept`, `reprocess`, `retry` and `discard` its newest version allows now, in that order
  (`Materials.actions`, the one place of the rule): `accept` and `discard` while it is `needs_review`, `reprocess` too
  when a page is unreadable; `retry` (when a cleaned original or a complete raw upload remains, and its format has a
  chain) and `discard` while it is `failed`; none in any other state (a `ready` version is never reprocessed nor
  discarded in place). The frontend shows only those.
  - `POST /api/v1/materials/{id}/{accept|reprocess|retry|discard}` takes no body and answers the `Material` view
    (`no-store`). `materials.service.MaterialActions` locks the newest version and acts only from its allowed state,
    in one transaction; anything else is `material.action_unavailable` (409). Another owner's material, an unknown or
    a malformed id are `request.not_found` (404), and nothing changes.
    - accept: `needs_review` becomes `ready`, and a `ready` version of the material before it `superseded`.
    - reprocess (`IngestionSteps.reprocess`): for each unreadable page, the narrowest recorded step with a page range
      that covers it (the latest of equally narrow ones; never a step over every text file) is copied, stage and
      input file, narrowed to each run of consecutive pages under it; the backend never names a stage. In the same
      transaction the `unreadable` outcomes go, with those pages' page views and pictures (removed after the commit),
      the passages and the outline; a held copy of the version's latest `texts` step is added; the version becomes
      `processing`, and the chain's end brings it back to `needs_review`. New steps past the step cap answer
      `ingestion.too_many_steps` (409) and change nothing. A new job starts at attempt 1, so each run is read in
      one child. A blank page comes back unreadable, and the report says so again.
    - retry (`IngestionSteps.retry`): from the cleaned original as a pipeline restart does (the restart's job-free
      core, `startOver`), or without one from the complete raw upload as the first step does; the version becomes
      `processing`. Past the step cap of a known page total: `ingestion.too_many_steps` (409).
    - reprocess of a `ready` version (Story 3.4, `IngestionSteps.reprocessAccepted`; `actions` lists `reprocess` for
      `ready`): after the check of a replacement (`NewVersions.lockReady`, material locked), a new version of the
      material, `waiting`, with the ready version's format, size and page total. Its `original` version file is a
      copy of the ready version's cleaned original under a new file id (`version_file` file ids are unique per
      owner; the copy goes if the transaction rolls back), and its first step is the format's start stage over that
      copy, as a pipeline restart. The ready version, its files, passages and points are not touched, and it serves
      until the new one is accepted. Any other state keeps the rules above (`needs_review`: in place).
    - discard: the version becomes `discarded` with `material_version.removal_pending` set, and its unended steps
      are cancelled. After the commit `VersionRemoval` runs once, as the owner, in three parts each safe to repeat:
      the page views, passages, outline, layers and outcomes (one transaction); the pieces of every version file;
      the `version_file` rows, the complete raw upload (`removing`, finished by the upload sweep) and the flag (one
      transaction). Step rows and the version row stay; they hold no content. `VersionRemovalSweep` (every
      `GMSHIELD_VERSION_REMOVAL_SWEEP_EVERY`, 10 min, the first right after the start) finishes every version still
      flagged, each as its owner; its list of flagged versions (`data.PendingRemovals`, `JdbcClient`) is the one read
      of a library table without an owner, and it reads ids only.
  - `GET /api/v1/materials/{id}/report` answers `{outline: [{level, title, page}], unreadable: [{firstPage,
    lastPage}], warnings: [{code, count}]}` (`no-store`) while the newest version is `needs_review` or `ready`, and
    `request.not_found` (404) in any other state. `unreadable` holds runs of consecutive pages; the warnings are facts
    the backend derives: `report.pages_unreadable` (the unreadable pages), `report.pages_unopened` (those with no
    page view: their reading crashed) and `report.outline_empty` (count 0), each only when it holds. The three counts
    are the material's `pages` (`done`, `readByOcr`, `unreadable`).
  - The page answer gains `outcome` (`read_directly`, `read_by_ocr`, `unreadable`, or null). An unreadable page with
    no page view now answers `{picture: null, lines: [], text: null, passages: [], outcome: "unreadable"}` instead
    of 404; its picture is still 404.
  - `V13__version_removal.sql`: `material_version.removal_pending boolean not null default false`, and a partial
    index on `(updated_at, id) where removal_pending`.
- **Photos as the pages of one material (`materials`, Story 1.21, FR-13, public contract 1.10.0, internal contract
  1.6.0).** Several images become one ordered set: one material, one version, one tus upload per image.
  - `POST /api/v1/image-sets` (`ImageSetController`, `materials.service.ImageSets`) takes `{name, kind?, language?,
    campaign? | system?, images: [{name, length}]}` and, in one transaction, creates the material (its name and
    details as a plain upload's), its version in `uploading` and one open upload per image at its place
    (`upload.position`, 1 to 500), each image's file name encrypted bound to its upload row (entity `upload`, field
    `name`). It answers `201 {id, materialId, expiresAt, images: [{position, uploadId}]}`; the set's id is its
    version's. The GM's "Enviar" confirms the order: there is no confirm route, and the order never changes. Past
    500 images or 2 GiB in all (`Uploads.MAX_IMAGES`, `MAX_LENGTH`): `upload.set_too_large` (413, params `maxImages`
    and `maxBytes`), nothing stored.
  - Each image then goes through the tus routes at `/api/v1/uploads/{uploadId}`, with the usual part and whole-file
    SHA-256 checks and its 6 hours. A member's first bytes must be an image's (`Format.IMAGE_CODES`), else
    `upload.format_refused` (415, params `accepted` the image formats). A refused format, a whole-file mismatch
    (`upload.file_checksum_mismatch`, 422) or an expiry (the upload sweep) fails that member alone: its pieces go, its
    row stays `failed` with `failure_code`, and the version stays `uploading`. A failed member is "not found" to the
    tus routes.
  - The set starts by itself (`Uploads.startIfReady`, with the version locked so two members completing at once start
    it once) when no member is open or failed and one at least is complete: the version becomes `waiting` with the
    format `images` (internal, never an accepted upload format) and the members' size in all, and its first step
    (stage `images`, input file the first member) is created. An image sent through the plain tus creation is a set
    of one: it starts at its completion like a PDF.
  - `POST /api/v1/image-sets/{id}/images/{position}` (`{name, length}`) replaces the failed image at that place with
    a new open upload (`201 {position, uploadId, expiresAt}`); `DELETE` the same address leaves it out: the places
    after it move up by one (`upload.closeGap`), and the set starts when it is now whole, or, the last image left out,
    the version is `discarded` (`200 {materialId, versionId, state}`). An image that has not failed is
    `upload.image_not_failed` (409); another owner's set, an unknown or malformed id, a set no longer uploading and a
    place it does not have are `request.not_found` (404).
  - The material view gains `imageSet: {id, images: [{position, uploadId, name?, state, offset, length, expiresAt,
    failure?}]}` while a set uploads (then `upload` is absent); `name` only while the image is not complete.
  - The images step's claim input (internal contract 1.6.0) is `input: "images"` and `inputs: [{name:
    "image-<uploadId>", format}]`, the complete members in order; `openInput` streams each to that step only, checked
    against the step's version and owner. A step of an image version whose input is a file of the version (a restart
    or retry from the assembled original) gets `source` as before. `START_STAGES` maps `images` to the `images`
    stage, for the start, a restart, a retry and Reprocessar. The first report's `original` (the assembled PDF) marks
    every member `removing`, as a PDF's raw upload is; a discard removes them all.
  - The upload sweep fails an expired member alone (`upload.expired`, its pieces removed) and discards a set nobody
    can finish any more: no member open, one at least failed, and every failed member past its expiry (version
    `discarded`, every member removed, complete ones included), so after 6 hours an unfinished set is gone.
  - `V14__image_sets.sql`: `upload.position`, `upload.name` (encrypted), `upload.failure_code`, the state `failed`,
    and the version format `images`. Rolling back past V14 is safe only while no failed upload or `images` version
    exists.
- **Index an accepted material (`materials`, Story 2.1, AD-10, internal contract 1.7.0).** The worker alone writes
  Qdrant; the backend records index jobs and their inputs, never a vector.
  - `IndexChanges`, kind `index.change` on the ingestion lane. The accept (`MaterialActions`) gives the version one
    job per run of at most 200 passages by ordinal, in the accept's transaction (an `index_change` row per job:
    owner, version, first and last ordinal). At the claim the input is `{source, owner, kind, language, tokenKey,
    passages: "passages"}`: the version's id, its owner, the material's kind and language (null when not given),
    and Base64 of `Vault.ownerTokenKey(owner)` (HKDF of the index key under `("index-token", owner)`, 32 bytes,
    never logged or stored). `GET …/inputs/passages` serves the run as JSON Lines `{id, ordinal, page, title,
    text}` (`title`: the last element of the section path, or empty), decrypted for that run only. Unlike a file
    input, the run is not streamed piece by piece: it is decrypted and buffered whole in the input's read transaction
    (at most 200 passages of at most 16,384 code points) and served from memory. The result must
    carry `indexRecipe` (`[A-Za-z0-9._-]{1,32}`, else `job.result_invalid`); it is kept on the row, and when no
    index change of the version is left unended and every row of its set reports one recipe,
    `material_version.index_recipe` records it. A superseded version keeps its points and its jobs run on.
  - **Records runs (Story 4.2 part B, migration V36).** When the version has records, its set also holds runs of its
    records, after its passages runs: each takes the next 200 records by first page, then the rest of the page where
    it would end, so no page is split across two runs (`index_change.records` true; `first_ordinal` and `last_ordinal`
    hold the run's first and last page). A records run's claim input names `records: "records"` instead of
    `passages`, and `GET …/inputs/records` serves JSON Lines `{id, page, type, name, fields: [{name, value}]}` (the
    record's id, first page and core type key, its name and fields decrypted through `ExtractedRecords.readRange`, for
    that run only; the line `index.records_opened` logs the count). The worker writes one point per record, its id the
    record's id. The version records its recipe only once its records runs are done too, and the retry sweep moves a
    failed records run like a passages run (`records` kept). `ScopeReads.pendingPassages`, `indexing` and `failed`
    read passages runs only, so a pending or failed records run changes neither the keyword fallback nor the GM's
    notices (records have no keyword fallback; Story 4.3 part B leaves them out of a search by name until their run
    is indexed, `ScopeReads.unindexedRecordRuns`). V36 is additive (`records boolean not null default false`); rolling back past it
    is safe only while no records row exists, and it leaves record points in Qdrant that an older worker's search
    would return, so each source's removal has to run again. Only a worker of this release reads a records run: an
    older one fails it with `index.input_invalid`, the retry sweep moves it (at most 3 times), and once its retries are
    spent the next reconcile (a backend start) cuts it again. Records exist only from pipeline "8", so deploy the
    backend, then the worker.
  - **Reconcile with records runs.** `IndexReconcile` calls `IndexChanges.reconcile`: when every passages run of a
    stale version's set already reports the stated recipe, only its records runs are deleted and cut again (the line
    `index.records_restarted` logs ids and counts), so a records run that failed for good never makes the passages be
    indexed again, and the GM's notices stay as they are; the version records the recipe once the new records runs are
    done (at once when it has no record left to cut). Otherwise the whole set is created again, as before.
  - The recipe (AD-10): `POST …/claims` takes an optional body `{indexRecipe}` (400 `request.invalid` for one that is
    not a recipe). A recipe other than the last one stated since the start (the first one always) publishes
    `jobs.service.IndexRecipeStated` after the claim, whatever its answer. `IndexReconcile` then runs off the
    request, one reconcile at a time: for each owner with a `ready` version under another recipe or none
    (`StaleIndexes`, the other read of a library table without an owner: ids only), in a transaction of its own as
    that owner, each such version with no unended index change gets a new set (its earlier rows go and its recipe
    is cleared). A claim without a body (a worker before 1.7.0) states nothing. A failed change leaves the version
    without a recipe; the retry sweep gives the failed run a new job (below), and once its retries are used, the next
    start's reconcile indexes the version again.
  - **Indexing at worker start (internal contract 1.12.0, public contract 1.16.0, migration V20).**
    - `PUT /internal/v1/index-recipe` takes `{indexRecipe}` (schema `IndexRecipeStatement`) and answers 204, or 400
      `request.invalid` for one that is not a recipe. It needs the worker's credential and no grant. It goes through
      `JobClaims.stateRecipe`, the same comparison as a claim: `IndexRecipeStated` is published only when the recipe
      differs from the last one stated (a claim's or a statement's), and the state is reset when publishing throws.
      The worker (from internal contract 1.12.0) states its recipe at every compatible check (at its start, then every
      30 s), so after a deploy the reconcile runs within one check, with no job claimed; restating every 30 s also
      covers a backend redeployed while the worker keeps running (the last recipe is kept in memory only).
    - A run is failed when its `index_change` row's job ended `failed` (`Jobs.failed`); a `cancelled` run is not.
      `ScopeReads.pendingPassages` counts failed runs as pending, so the keyword fallback still reads their passages;
      `ScopeReads.indexing` does not.
    - `IndexRetrySweep` (every `GMSHIELD_INDEX_RETRY_EVERY`, 10 min, the first one period after the start; switched off
      by `gmshield.materials.index-retry-sweep.enabled=false`) visits each owner with a failed `index.change` job
      (`Jobs.ownersWithFailed`: ids only) as that owner, in a transaction of its own. Each run of a `ready` or
      `superseded` version whose job failed and whose `retries < 3` gets, under the version lock, a new
      `index.change` job for the same ordinal range: the row moves to the new job (`job_id` is the key; the failed job
      row stays as history) and `retries` goes up by 1 (line `index.change_retried`). It never starts a whole version
      again, and never reads a worker's failure code. `IndexChanges.start` (an accept, a reconcile) begins every row at
      0, so a run that used its 3 retries is indexed again by the next reconcile only.
    - `V20__index_change_retries.sql` adds `index_change.retries smallint not null default 0` (0 to 3). Additive: the
      previous release keeps working on it.
    - `CurrentConversation.indexingFailed` (schema `IndexingFailure`, required and nullable) is `{passages, retrying}`:
      the distinct in-scope passages of the merged failed runs (at least 1), and whether any of those runs has
      `retries < 3`; null when none failed. `indexing` is unchanged.
    - `Scopes.MAX_PENDING` is 5,000 per tier (the worker reads them within a time budget); the line
      `scope.question_input` logs `pendingLeftOut`, the pending passages past it.
    - **Deploy order: backend, then frontend (its minimum public contract becomes 1.16.0), then worker (it states its
      recipe only to a backend at 1.12.0 or later; its minimum stays 1.11.0).** The worker and the frontend do not
      depend on each other here. Roll back in the reverse order.
  - `IndexRemovals`, kind `index.removal` on the ingestion lane: a discard cancels the version's unended index
    changes and creates one in its transaction (Story 3.4's delete will reuse it); its input is `{source, owner}`,
    and the worker deletes the points by a filter on both.
  - `V15__index_jobs.sql`: `material_version.index_recipe`, `index_change` and `index_removal` (library tables, every
    key with the owner; `index_removal` has no reference to the version so it outlives a deleted one). This release
    writes them at once (Dalton, 2026-11-18: no earlier release holds an index row). **A backend rolled back below
    this release must first cancel every unended `index.change` and `index.removal` job**, as `Jobs.cancel` ends a
    job (the grant and claim times cleared, the claim id kept), with the backend stopped, as `gmshield_backend`:

    ```sql
    update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null,
                   outcome_code = null, updated_at = now()
     where kind in ('index.change', 'index.removal') and state in ('waiting', 'offered', 'running');
    ```

    Their messages left in `gmshield:work:ingestion` are refused at the claim (`job.not_offered`) and acknowledged.
- **What a campaign may use (`scope`, Story 2.2, AD-9, internal contract 1.8.0).** The one resolver: no other module
  reads library items for a campaign.
  - `ScopeSettings(campaignTier, systemTier, otherTier, modelKnowledge, materialsOff)`, a parameter of every resolve
    (Story 2.4 freezes it with a job; Story 3.5 stores it per campaign, below). `ScopeSettings.STARTING` is campaign
    and system on, other and model knowledge off, every material on.
  - `Scopes.resolve(campaignId, settings)`, as the bound owner in one read transaction, answers a `ResolvedScope`
    (made only by `Scopes`): the campaign tier is the `ready` version of each material whose `campaign_id` is the
    campaign; the system tier the `ready` version of each material attached through `material_system` to the
    campaign's system (one already in the campaign tier is left out); a tier switched off has none. A source is a
    version id, the `source` of the Qdrant payload. `pending` is the ids of the in-scope passages whose ordinal falls
    in the run of an `index.change` job that has not ended, kept by tier (`pending(tier)`). A campaign not found
    (unknown, or another owner's) is 404 `request.not_found`. Where a material belongs is read afresh on every
    resolve (Story 3.3): a material moved to another campaign or system leaves this scope at the next resolve, and a
    material of several systems is in the system tier of each one's campaigns.
  - `Scopes.passages(scope, ids)` checks every id first: any id that is not a passage of an allowed source still
    `ready` (unknown, another owner's, a version no longer `ready`, out of every tier) refuses the whole request with
    403 `scope.passage_refused`, which names no id, before anything is decrypted. Otherwise it answers
    `{id, tier, text, sectionPath, page}` in the order asked, each id once.
  - It reads the library only through `materials.service.ScopeReads` (the ready versions by campaign and by system,
    the pending passage ids of a set of versions, checked passage reads by id; JPQL, filtered by the owner, no
    migration). `ScopeReads` and its `Passage` are the only types of `materials`' named interface `service`, and
    `Materials.passage(UUID)` is package-private. Lines carry ids and counts, never a text or a title.
  - **A replaced material (Story 3.4).** The tiers read every `ready` version, and a material has at most one: a new
    version starts only while the material is `ready` with nothing else on its way, and accepting it supersedes the
    old one. So the old version serves every scope while its replacement is processed, and the new one alone after
    the accept; a reference stored with a turn still opens the superseded version (`CITED`). `scope.passage_refused`
    stays non-retryable: an accept in the middle of a question fails that one answer clearly rather than quietly
    changing the sources under it.
  - `POST /internal/v1/jobs/{jobId}/claims/{claimId}/passages` (`jobs`), with `X-Job-Grant`: body `{ids: [uuid]}`, 1
    to 100 non-null ids. A malformed body or an id that is not a UUID is 400 `request.invalid` before anything else;
    then the grant is checked (403 `job.grant_refused`), then the ids (400 `job.passages_invalid` for none, more than
    100 or a null one). As the job's owner, in a read-only transaction, the job's handler answers through
    `JobHandler.passages(JobRef, ids)`, whose default is 403 `job.passages_refused`; through `Scopes`, a job whose
    campaign is gone is 404 `request.not_found` and any id out of the scope 403 `scope.passage_refused`. The answer
    is `{passages: [{id, tier, text, sectionPath, page}]}` (`JobPassage`), never cached. From Story 2.4 the
    production kind `question.answer` implements it through `Scopes`; a test-only handler (`ScopedPassagesFlowTest`)
    proves the path too.
  - **The question input (Story 2.3).** `Scopes.questionInput(scope, question)` is the one builder of the input of a
    job that carries a question, from a `ResolvedScope` only, as its owner: `{question, owner, modelKnowledge, tiers:
    [{tier, sources, pending}], tokenKey}` (`modelKnowledge` from Story 3.8, below). Both tiers are always listed, in
    order (campaign, then system), a tier without a source with empty lists; `pending` is that tier's pending passage ids, at most the first `Scopes.MAX_PENDING`
    (5,000 from indexing at worker start, 100 before; the worker reads them 100 at a time within a time budget) in
    order, so a re-index of a whole library never fills the claim reply (the total is logged as a count, `pending`,
    beside `pendingListed` and `pendingLeftOut`); `owner` is the scope's owner, for the worker's Qdrant
    filter; `tokenKey` is Base64 of `Vault.ownerTokenKey(owner)`, whose buffer is zeroed once encoded (so `scope` may
    use `vault :: service`). From internal contract 1.16.0 (Story 3.10) the answer job adds `history` after this builder
    (below). The question is 1 to 4,000 characters (code points), else
    `IllegalArgumentException`; a scope of another owner is `IllegalStateException`. Provider, model and tier come
    with the claim (Story 2.10, `model`), never in this input. The handler's `input(JobRef)` returns it, so the claim reply carries it unchanged in `input`
    (no claim schema change). Lines (`scope.question_input`) carry counts, never the question or the key. No
    production kind carries a question until Story 2.4; the test-only kind `test.question` (`ScopedPassagesFlowTest`)
    proves that the claim reply carries the input and that the passages route serves the ids the worker found. The
    worker's search (`GMshield-worker`, `retrieval/search.py`) needs contract 1.8.0, unchanged here.
- **A closed scope across three tiers (`scope`, Story 3.5, public contract 1.25.0, internal contract 1.13.0,
  migration V27).** Each campaign's scope settings are stored in PostgreSQL only (never in a Qdrant point, and no
  point is rewritten on a change, AD-10), and resolved by `Scopes` alone.
  - **Storage.** `scope_setting` (owner, campaign with an owner-scoped key to `campaign`, `campaign_tier`,
    `system_tier`, `other_tier`, `model_knowledge`, `version` as Hibernate `@Version`, created and updated times;
    primary key (owner, campaign)) and `scope_material_off` (owner, campaign, material; primary key all three, no
    reference to `material`), library tables through JPA only (`scope.data.JpaScopeStore`). A campaign with no row
    reads as `STARTING` at version 0; the first change inserts the row at version 1 (a concurrent first insert hits
    the key and is a stale version), so `library` writes nothing here when it creates a campaign. A material is on
    unless it is listed as off, so a newly attached material starts on; an id whose material is gone is ignored when
    resolving and dropped by the next change, which stores exactly the ids it lists.
  - **Resolution.** The campaign tier is the `ready` versions of the campaign's materials; the system tier those of
    its system's materials, minus the campaign tier's (while it is on); the other tier every other `ready` version of
    the owner (the materials of the GM's other campaigns, archived ones included, and other systems; never one of the
    campaign's or its system's, whatever the switches; read only while the tier is on). A tier switched off has no
    source; a material switched off is left out of whichever tier it falls in. `Tier.OTHER` (`other`) follows
    campaign and system everywhere, the question input's `tiers` included (three entries, always). `ScopeReads`
    gains `readySourcesOfCampaign`, `readySourcesOfSystem`, `readySourcesOfOwner` (each version with its material)
    and `materialLabels` (decrypted names).
  - **Freezing.** `Conversations.ask` reads the stored settings (`CampaignScopes.settings`) and freezes them on the
    turn: `turn.other_tier` (default false) beside the V17 switches, and one `turn_material_off` row (owner, turn,
    material) per material off. `AnswerJobs` rebuilds the `ScopeSettings` from the turn on every passage fetch and
    reference read, so a change made while a turn is answered applies from the next question and never turns into
    `scope.passage_refused` mid-answer. `GET /api/v1/conversations/current` uses the stored settings.
    `modelKnowledge = true` is stored and frozen; from Story 3.8 it lets the model add its own knowledge (below).
  - `GET /api/v1/campaigns/{id}/scope` answers `{version, campaignTier, systemTier, otherTier, modelKnowledge,
    materials: [{id, name, tier, on}]}`: the materials of every tier whatever the tier's switch, each with a `ready`
    version, in tier order, then newest first (`Scopes.members`).
  - `PUT /api/v1/campaigns/{id}/scope` takes `{version, campaignTier, systemTier, otherTier, modelKnowledge,
    materialsOff: [ids]}` and answers the new view, its version raised by one. A version the settings have left is
    409 `request.stale_version`; a missing field, a negative version, a null or malformed id, more than 1,000 ids or
    a malformed body is 400 `request.invalid`; an id that is not the owner's material, or in none of the campaign's
    tiers, is 400 `scope.material_unknown`; nothing changes then. Both routes answer 404 `request.not_found` for
    another owner's, an unknown, a malformed or a deleting campaign, and send `Cache-Control: no-store`. Logs:
    `scope.settings_changed` (ids, switches and counts).
  - **The ground "the current scope".** `GET /api/v1/campaigns/{campaignId}/sources/{versionId}/pages/{page}` and
    `.../picture` (`conversations`, beside the reference routes, through `CampaignScopes.sourcePage`) serve a page of
    a version that is an allowed source of the campaign's current stored scope (`Scopes.resolveSources`), with the
    reference page's shape and headers; anything else is 404 `request.not_found`, never telling whether the version
    exists. A reference stored with a turn keeps its own ground and ignores the current settings: a source that has
    left the scope still opens there, and nothing new is drawn from it.
  - **Deletion.** `scope.service.ScopeDeletion` is a `SystemDeletionHandler` (part `scope`, counting 0): a system's or
    a campaign's removal deletes the settings and switch rows of the campaigns going (before their rows go), a
    material's removal its switch rows in every campaign; a version's removal takes nothing.
  - **Internal contract 1.13.0.** `JobPassage.tier` allows `other`. The worker (`retrieval.search.TIERS`) takes turns
    across the three tiers and requires 1.13.0.
  - Deploy order: the backend first (V27 is additive; until a GM changes a campaign's scope every campaign resolves as
    before), then the worker (its minimum backend contract becomes 1.13.0) and the frontend (contract copy only; its
    minimum stays 1.24.0). **Between the backend and the worker deploys questions fail**: the question input now always
    lists the `other` tier, which a 1.12 worker refuses as `retrieval.input_invalid` (not retried), so deploy the
    worker right after the backend. Rolling the backend back below 1.25.0: the scope tables and `turn.other_tier` are
    ignored (every campaign resolves as `STARTING`, a frozen turn by its V17 switches alone), and the worker must go
    back first, since it refuses a 1.24 backend.
- **The campaign's version leads (`conversations`, Story 3.7, public contract 1.26.0, internal contract 1.14.0,
  migration V28).** The tier shown with an answer is always the backend's: the worker copies `JobPassage.tier` onto
  each passage it hands the model and never works one out.
  - **The tier marker.** `JobStreamEvent` gains type `tier` with a field `tier` (`campaign`, `system` or `other`) and
    `offset`: the model opened a tier's version of the answer there (the campaign's first, then each other tier's as
    an alternative, when the sources disagree). A `tier` event with any other field, no offset, a negative one, or
    another word is `job.events_invalid` (400, nothing stored). `AnswerJobs.events` drops a marker whose tier has no
    source in the turn's frozen scope (`conversation.tiers_dropped`, a count) and stores the rest encrypted with the
    batch's events, like a citation (`{"type": "tier", "tier", "offset"}`). The stream relays it as the public event
    `tier` (`TurnTierEvent` `{tier, offset}`) and replays it after `Last-Event-ID`.
  - **The reference's tier.** `turn_reference.tier` (V28, nullable, checked to the three words) is written once with
    the reference, from `ResolvedScope.tierOf(versionId)` on the turn's frozen scope (`CitedPlace.tier`): a source that
    leaves the scope, or is removed, keeps the tier it had. `TurnSourceReference` gains an optional `tier`; a row
    written before V28 has none, shows no tier and still opens. No backfill.
  - Deploy order: backend → frontend → worker. The backend first (V28 is additive; a 1.13 worker never sends a
    `tier` event), then the frontend (its minimum becomes 1.26.0), then the worker (its minimum becomes 1.14.0).
    Rolling the backend back below 1.26.0: the column is ignored, and the worker and the frontend must go back first.
- **Model knowledge only when the GM turns it on (`conversations`, `scope`, Story 3.8, public contract 1.27.0,
  internal contract 1.15.0, no migration).** The campaign's `modelKnowledge` switch (stored since V27, frozen on each
  turn since V17) now reaches the worker and decides whether an answer may hold a part from the model's own knowledge.
  - **The question input.** `Scopes.questionInput` adds `modelKnowledge` (boolean, always present): the switch of the
    scope's settings, which for an answer job are the turn's frozen settings, so a change applies from the next
    question. It changes no source, no pending passage and nothing the passages route hands over. The worker gives the
    model a fixed extra rule only when it is true, and reads an absent field as false.
  - **The model tier word.** `JobStreamEvent.tier` (internal 1.15.0) and `TurnTierEvent.tier` (public 1.27.0) allow
    `model`: the model's own knowledge starts at that offset. It is a turn-event tier word only (`JobEvent.MODEL`,
    `TurnEvent.MODEL`): `Tier`, `JobPassage.tier`, `turn_reference.tier` and `TurnSourceReference.tier` keep their
    three words, so a reference never carries it. The batch check accepts it with the same fields as any tier marker.
    `AnswerJobs.events` stores a `model` marker only when the turn's frozen `modelKnowledge` is on; otherwise it is
    dropped (`conversation.tiers_dropped`) and never relayed. A kept one is stored, relayed and replayed like any tier
    marker.
  - **The done event.** `TurnDoneEvent` gains the required `modelKnowledge` (the turn's frozen switch, read from the
    turn row, so a turn asked before 3.8 answers its stored value), and `silent` becomes "not stopped, no reference
    and no `model` tier event". `Conversations.ending(turnId)` reads the references, the switch and whether a `model`
    marker was stored (it reads the turn's events only when the switch is on) in one transaction, when `done` is sent.
    The frontend uses `modelKnowledge` to offer the scope under a silent answer only when the switch was off.
  - Deploy order: backend → worker → frontend. The backend first (a 1.14 worker ignores the new input field and never
    sends a `model` marker), then the worker (its minimum becomes 1.15.0; the old frontend cannot turn the switch on,
    so nothing it can set is ignored), then the frontend (its minimum becomes 1.27.0), last so the switch only turns
    on once the worker honours it. Rolling the backend back below 1.27.0: the frontend and the worker must go back
    first.
- **Search the library (`conversations`, `scope`, Story 3.9, public contract 1.28.0, internal contract unchanged
  1.15.0, migration V29).** A GM finds a passage by meaning or exact words without asking a question. Search is a job
  on the interactive lane, like a question, and its text is never matched in SQL.
  - `POST /api/v1/campaigns/{campaignId}/searches` with `{query, tier?, materialId?}` answers 202 `{id}`
    (`createSearch`). The query is 1 to 500 code points, not blank; `tier` is `campaign`, `system` or `other`;
    `materialId` a material's id; each filter may be absent or null. Anything else is 400 `request.invalid`, a campaign
    that is not the account's 404 `request.not_found`, with nothing recorded. In one transaction (`Searches.create`):
    the `search` row with the query encrypted (binding `search`/`query`), its filters and state `pending`, and its job
    of kind `library.search`; then the owner's searches past the newest 20 are pruned (their unended jobs cancelled,
    then their results and rows).
  - `GET /api/v1/searches/{searchId}` answers `{id, campaignId, query, tier, materialId, state, code?, results}`
    (`getSearch`, schema `Search`): `state` is `pending`, `done` or `failed`; `code` only when failed; `results` (empty
    unless done) up to 20 `{passageId, versionId, materialName, sectionPath, page, firstWords, tier}` in rank order.
    Each stored result is re-read on every `GET` within the campaign's **current** scope narrowed by the search's
    filters (`Scopes.results`): one whose source left the scope since (a tier or material switched off, a version no
    longer `ready`) is dropped, never refused, and the tier is the one the scope gives now. `firstWords` is the
    passage's text with its runs of white space made one space, cut to its first 160 code points at a word boundary.
    Another owner's search, an unknown or malformed id, a pruned one, and one whose campaign was deleted since are the
    same 404. `/api/v1/searches/**` joins the library routes (`LibraryOwnerFilter`). The result's page opens on the
    ground of the current scope (`getScopeSourcePage`, Story 3.5); there is no new ground.
  - **The scope narrowed (`ScopeFilter`).** `Scopes.resolve(campaign, settings, filter)` and `resolveSources(…)` keep
    only the allowed sources the filter keeps, before the pending passages are read: a tier filter empties the other
    tiers' `sources` and `pending`, a material filter keeps only that material's version (and its pending passages).
    A filter only narrows: a tier or material switched off, or a material in no tier, leaves nothing.
  - **The job (`SearchJobs`, `library.search`, interactive, `callsModel(job)` false).** Its input is
    `Scopes.searchInput`: the question input's shape (`{query, owner, modelKnowledge, tiers: [{tier, sources,
    pending}], tokenKey}`) with `query` in place of `question`, within the search's campaign's current stored settings
    narrowed by its filters. Its passages route (the worker reads the candidates' text to put the exact query first)
    serves that same narrowed scope through `Scopes.passages`, so an id outside it is 403 `scope.passage_refused`.
    `completed` takes `result.ids`: 0 to 20 distinct passage ids in rank order (anything else is 400
    `job.result_invalid` and the search stays pending). Every id is checked against the narrowed scope first
    (`Scopes.references`, which decrypts no text): when any is not a passage of an allowed source still `ready`, the
    whole result is refused and the search ends `failed` with `scope.passage_refused` (the job itself is done).
    Otherwise `search_result` stores each id with its rank, version and tier, and the search ends `done`. The job's end
    in `failed` (a reported failure or a lapsed claim after the last attempt) ends the search `failed` with its code.
  - The worker's side: `GMshield-worker` `lanes/search.py` (README there). Its result is ids only.
  - `V29__search.sql`: `search` (owner `@TenantId`, campaign with a reference to it, job, encrypted query, tier,
    material by value, state, code: a failed search has one and only it) and `search_result` (search, rank 1 to 20,
    passage, version and tier by value; a (search, rank) and a (search, passage) once). Library tables: every key
    with the owner, JPA and JPQL only. The campaign's and the system's deletion (`SystemConversationsDeletion`)
    cancel the searches' jobs with the answers' and remove the searches with their results; a material's deletion takes
    none (the next read drops its results).
  - Lines (`search.created`, `search.done`, `search.failed`, `search.result_refused`, `search.read`,
    `search.results_dropped`, `scope.search_input`, `scope.results_read`) carry ids, counts and codes, never the query
    or a passage's text.
  - Deploy order: backend → worker → frontend. The backend first (V29 is additive; nothing creates a search until the
    frontend does), then the worker (it adds the `library.search` handler), then the frontend (its minimum becomes
    1.28.0). **A backend rolled back below this release must first cancel every unended `library.search` job**, with
    the backend stopped, as `gmshield_backend`; the two tables are then left unread:

    ```sql
    update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null,
                   outcome_code = null, updated_at = now()
     where kind = 'library.search' and state in ('waiting', 'offered', 'running');
    ```

- **Conversations by area (`conversations`, Story 3.10 part A, public contract 1.29.0, internal contract 1.16.0,
  migration V30).** A campaign has many conversations, each in one area of the sidebar: `questions` (Consultas) or
  `rules` (Criação de Regras). A conversation is made by its first question, never by "Nova conversa", so none is
  empty. Deleting a conversation is part B (below).
  - `V30__conversation_areas.sql`: `conversation` gains `area` (checked to the two words; existing rows `questions`),
    `name` (bytea, null until the GM renames it; vault binding `conversation`/`name`), `version` (Hibernate `@Version`,
    raised by a rename only) and `last_asked_at` (an existing row takes its newest turn's time; asking moves it forward
    by one JPQL bulk update that never moves it back and never raises the version, so an open rename stays valid). The unique key
    `conversation_owner_campaign_key` is dropped, and `conversation_owner_campaign_area (owner_id, campaign_id, area,
    last_asked_at)` serves the lists.
  - `GET /api/v1/campaigns/{campaignId}/conversations?area=&limit=&offset=` (`listConversations`) answers `{items:
    [{id, area, name, questions, createdAt, lastAskedAt, version}], nextCursor?, total}`: the area's conversations,
    the most recently asked first (then by id), `limit` 1 to 50 (20 by default), `offset` 0 to 100,000; `nextCursor`
    is the next page's offset as text, absent on the last. A missing or unknown area, a limit or an offset outside its
    range is 400 `request.invalid`; a campaign that is not the account's is 404.
  - `GET /api/v1/conversations/{id}` (`getConversation`) answers `{id, campaignId, area, name, version}`;
    `GET /api/v1/conversations/{id}/turns` (`listConversationTurns`) answers `{items: [{id, question, language,
    createdAt, events: [{sequence, type, data}]}]}`: the newest 200 turns, oldest first, each event's `data` as the
    stream sends it (a `done`'s built when read, as on the stream). A turn still being answered has no final event
    yet; its stream continues after its last sequence.
  - `PATCH /api/v1/conversations/{id}` (`renameConversation`) with `{version, name}`: the name trimmed, 1 to 120
    code points (else 400 `request.conversation_name_invalid`, params `min` and `max`; a missing or null `name` too),
    stored encrypted; a version the row has left is 409 `request.stale_version`; no version or a malformed body is 400
    `request.invalid`.
  - `name`, in every view, is the stored name, or else the first question's first 60 code points cut at a word
    boundary (`Searches.firstWords`).
  - `POST /api/v1/campaigns/{campaignId}/turns` gains optional `conversationId` (continue it: it must be one of this
    campaign's conversations, else 404 with nothing recorded) and `area` (start a new conversation of it; with
    `conversationId` it must be that conversation's, else 400). With neither, the campaign's latest `questions`
    conversation is continued (made when it has none), as before, so the previous frontend keeps working during the
    deploy. `GET /api/v1/campaigns/{campaignId}/conversation` answers that same latest `questions` conversation.
  - Every one of these routes answers `no-store`, and another account's conversation, an unknown or malformed id and
    one of a campaign being deleted (from part B, a conversation being deleted too) are the same 404
    `request.not_found`.
  - **History (internal 1.16.0).** `AnswerJobs.input` adds `history: [{question, answer}]` after
    `Scopes.questionInput` (whose `textInput`, shared with the search input, is unchanged): the last 6 turns before
    this one in its conversation that ended `done` and were neither stopped nor (Story 3.11) dismissed
    (`Conversations.history`), built for each claim (so a claim already made keeps what it was given), oldest first,
    each answer its joined `delta` texts (they hold no citation marker) cut at 4,000 code points; `[]` for a first
    question.
    Retrieval still reads the question alone, and the history is never a source: the worker puts it inside the
    document delimiter as escaped data and never gives its ids. The line `conversation.history_read` carries the count
    only.
  - Lines (`conversation.created`, `conversation.renamed`, `conversation.history_read`, `conversation.turn_recorded`
    with its area) carry ids, counts and words, never a name, a question or an answer.
  - Deploy order: backend → worker → frontend. The backend first (V30 is additive for the previous release, whose
    inserts take the defaults; a 1.15 worker ignores the unknown `history` field), then the worker (its minimum
    becomes 1.16.0), then the frontend (its minimum becomes 1.29.0). Rolling the backend back below 1.29.0: the
    frontend and the worker go back first; the old backend continues the oldest conversation of a campaign whatever
    its area, and ignores the names.

- **Delete a conversation (`conversations`, Story 3.10 part B, public contract 1.30.0, migration V31, AD-22).** The
  recorded, resumable deletion of a system (3.1 part B), extended to a `deletion` row of kind `conversation`, recorded
  as a material's is (3.4 part B). What goes: the conversation, its turns, their event batches, `turn_material_off`
  rows and source references. Nothing of the other conversations, its campaign, the searches or the materials changes;
  `model_call_usage` stays (it names a job, never a turn). No undo, no bulk delete.
  - `V31__conversation_deletion.sql`: `conversation.state` (`ready` | `deleting`, default `ready`, checked), and
    `deletion_kind_check` widened with `conversation`. Additive: the previous release neither reads nor writes the
    state (its inserts take the default).
  - `GET /api/v1/conversations/{id}/deletion` (`getConversationDeletion`) answers `{questions}` (its turn count), what
    the confirmation dialog names.
  - `DELETE /api/v1/conversations/{id}` (`deleteConversation`) answers 202 with no body. `Conversations.delete` checks
    the conversation (the account's, `ready`, its campaign the account's), then `SystemDeletion.deleteConversation`
    writes, in one transaction, the `deletion` row (once per target) and each handler's `prepare` with
    `Going.ofConversation(id)`: `conversations` sets `state = 'deleting'` (a JPQL bulk update after the owner's row was
    read: it never raises the version) and locks and cancels the conversation's unended answer jobs; `not_before` is
    the latest lapse of those claims, or now. `materials` and `scope` ignore it. `library` keeps the id opaque. Logs:
    `conversation.delete`, `library.conversation_deleting`, `conversation.deletion_prepared`, then
    `conversation.deletion_removed` and `library.conversation_deleted` (ids, counts and times only).
  - From that commit the conversation is `request.not_found` everywhere: its campaign's list and `total`, GET, `/turns`,
    PATCH, DELETE and `/deletion`, a question naming it, the area-less question's latest `questions` conversation (the
    question continues the next latest one, or makes one), `GET /campaigns/{id}/conversation`, a campaign's or a
    system's deletion counts; and its turns' events, stop and reference pages and pictures. The cancel ends the answer
    jobs' claims (a later worker call is refused, `job.grant_refused`); a job call already under way when the
    conversation turned deleting still reads it (`Conversations.storedEvents`,
    `ConversationStore.findConversationAnyState`) and ends normally.
  - Removal at `not_before`, as a one-shot after the commit and through the sweep (which resumes it after a restart):
    in one transaction the references, then each turn with its batches and materials-off rows, then the conversation;
    then `deletion.done_at`. Every step is safe to repeat.
  - A campaign or a system deleted meanwhile takes the conversation too (`conversationsOfCampaigns` reads every state);
    the conversation's own deletion then finds nothing left and is marked done.
  - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.30.0). The worker does
    not change. After a rollback a deleting conversation shows again, with whatever its removal has not removed yet;
    that release's sweep takes a pending `conversation` row as a system it cannot find and marks it done. **Before
    rolling forward again**, make such conversations ready, or they stay `deleting` (hidden) forever with no pending
    deletion to remove them, so the GM can delete them again:
    ```sql
    update conversation c set state = 'ready'
     where c.state = 'deleting'
       and not exists (select 1 from deletion d where d.kind = 'conversation' and d.target_id = c.id
                                                 and d.done_at is null);
    delete from deletion d
     where d.kind = 'conversation' and d.done_at is not null
       and exists (select 1 from conversation c where c.id = d.target_id);
    ```
    The second statement drops the record the old sweep ended without removing anything: a deletion is recorded once
    per target, so it would otherwise answer a new delete of the conversation as not found.

- **Dismiss an answer (`conversations`, Story 3.11, public contract 1.31.0, migration V32, FR-31).** The GM removes an
  answer they do not want from the conversation, with an undo. No list of dismissed answers.
  - `V32__turn_dismissal.sql`: `turn.dismissed_at timestamptz` (null: shown). Additive: the previous release neither
    reads nor writes it.
  - `PUT /api/v1/turns/{id}/dismissal` (`dismissTurn`) answers 204 and sets `dismissed_at` when it is null;
    `DELETE /api/v1/turns/{id}/dismissal` (`restoreTurn`) answers 204 and clears it. Both are safe to repeat, and
    neither moves the conversation's version or `last_asked_at`. Only an ended turn is dismissed (`stopped_at` set, or
    its last stored event `done` or `error`): otherwise 409 `conversation.turn_running` and nothing changes. Another
    account's turn, an unknown or malformed id and a turn of a deleting conversation are 404 `request.not_found` on
    both routes (`Conversations.shown`).
  - `TurnEntity.dismissedAt` is mapped `updatable = false`: only the store's JPQL bulk updates
    (`JpaConversationStore.markDismissed`, `clearDismissed`, each after the owner's row was read) write it, so a stop's
    full-row flush of a turn loaded before a dismissal committed never undoes it (`ConversationStateIntegrationTest`).
  - From the dismissal's commit: `newestTurns` (so `/turns` and `Conversations.history`), `turnCount` and `turnCounts`
    (the list's `questions`, `GET /campaigns/{id}/conversation` and `/deletion`) take shown turns only. The history is
    built for each claim of a question's job (a lapsed claim re-offered builds it again), so a claim already made keeps
    the history it was given. Unchanged: the default name (`firstTurns` still takes the first
    question, dismissed or not, so a name never changes on its own), the turn's own routes (events, stop, references
    and their pages), and the conversation's deletion (`turnsOf` takes every turn).
  - Lines `conversation.turn_dismissed` and `conversation.turn_restored` carry the owner, the turn and whether it
    changed.
  - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.31.0). The worker and
    the internal contract (1.16.0) do not change. After a rollback every dismissed turn shows again and is sent as
    history again; rolling forward hides them again.

- **Bookmark an answer (`conversations`, Story 3.12, public contract 1.32.0, migration V33, FR-34, FR-36).** The GM
  saves a useful answer and finds it again from a list across all their campaigns. No notes or folders.
  - `V33__turn_bookmark.sql`: `turn.bookmarked_at timestamptz` (null: not saved) and the partial index
    `turn_owner_bookmarked` on `(owner_id, bookmarked_at)` where it is not null. Additive: the previous release
    neither reads nor writes it.
  - `PUT /api/v1/turns/{id}/bookmark` (`bookmarkTurn`) answers 204 and sets `bookmarked_at` when it is null;
    `DELETE /api/v1/turns/{id}/bookmark` (`unbookmarkTurn`) answers 204 and clears it, whatever the turn's state. Both
    are safe to repeat (a second save keeps the first time), and neither moves the conversation's version or
    `last_asked_at`. Only a done turn is saved (not stopped, its last stored event a `done` not marked stopped, and
    not silent: it has a source reference, or a model-knowledge part on a turn whose model knowledge was on, as the
    `done` event's `silent` says): a running, stopped, failed or silent one is 409 `conversation.turn_not_done` and
    nothing changes. Another account's
    turn, an unknown or malformed id and a turn of a deleting conversation are 404 `request.not_found` on both routes.
  - `TurnEntity.bookmarkedAt` is mapped `updatable = false`, as `dismissedAt`: only the store's JPQL bulk updates
    (`JpaConversationStore.markBookmarked`, `clearBookmarked`) write it (`ConversationStateIntegrationTest`). Deleting
    a turn by any route (its conversation, campaign or system) deletes the row, so the bookmark goes with it.
  - `GET /api/v1/conversations/{id}/turns`: each turn gains `bookmarked`.
  - `GET /api/v1/saved-answers?limit&offset` (`listSavedAnswers`, under the library routes' session edge) answers
    `{items, nextCursor, total}` as the conversations list does: ordered `bookmarked_at desc, id desc`, `limit` 1 to
    50 (default 20), `offset` 0 to 100000, else 400 `request.invalid`. Each item: `turnId`, `conversationId`,
    `campaignId`, `campaignName`, `area`, `conversationName` (the list's name rule), `savedAt` and `excerpt` (the
    answer's joined `delta` texts with Markdown stripped by `conversations.service.Markdown`, white space flattened,
    cut at a word boundary to at most 280 code points by `Searches.firstWords`). `Conversations.saved` reads the
    campaigns of the owner's listed saved turns (`savedCampaigns`), keeps those `Library.places` holds and that are
    not archived (so a campaign being deleted or archived drops out, and an archived one comes back on its restore),
    then pages `savedTurns` and counts `savedCount` within them: both join each saved turn to its `ready`
    conversation on owner and id and leave out a dismissed turn (its bookmark is kept, so the undo lists it again).
  - No standing as a source: neither the scope, retrieval nor `Conversations.history` reads the column
    (`ConversationFlowTest` asserts the history and the passages handed over are the same with and without it).
  - Lines `conversation.turn_bookmarked` and `conversation.turn_unbookmarked` carry the owner, the turn and whether
    it changed.
  - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.32.0). The worker and
    the internal contract (1.16.0) do not change. After a rollback the bookmarks are kept but not shown; rolling
    forward shows them again.

- **The core vocabulary in every system (`knowledge`, Story 4.1, public contract 1.33.0, migration V34).** The eleventh
  module holds each system's ontology; Story 4.1 stores its record types. No term, record, extraction or proposal yet
  (Stories 4.2 to 4.6), and nothing comes from model knowledge.
  - `V34__record_type.sql`: `record_type (id, owner_id, system_id, core, name, name_fingerprint, version, created_at)`.
    `core` is null or one of the eight core keys (`record_type_core_check`): `rule`, `spell`, `item`, `creature`,
    `character_sheet`, `encounter`, `dungeon`, `note`. The composite key `(owner_id, system_id) → system(owner_id, id)`
    keeps a record type within its owner; `(owner_id, system_id, core)` and `(owner_id, system_id, name_fingerprint)`
    are unique (their indexes serve the reads by system). Additive: the previous release neither reads nor writes it.
  - The name is a vault ciphertext (entity `record_type`, field `name`, the row, the owner) beside the keyed fingerprint
    of its `Library.uniqueForm`. A core type is identified by its plaintext `core`; its stored name is the key in
    English (`character_sheet` → "character sheet", `service.CoreRecordTypes`), so a type approved later (Story 4.4)
    can never take a core type's name. The frontend shows its own translation of `core`, never the stored name.
  - Seeding: both paths that create a system (`Library.createStarter`, `Library.createSystem`) publish
    `library.service.SystemCreated(ownerId, systemId)` inside their transaction; `Ontology.systemCreated`, a synchronous
    `@EventListener`, inserts the eight core types in that transaction, so they exist with the system or not at all
    (`knowledge.core_types_seeded`). `library` depends on nothing of `knowledge` (`ModuleBoundaryTest`).
  - Systems from before V34: `service.CoreTypeBackfill` runs once the application is ready. `data.JdbcCoreTypeBackfill`
    lists, across owners and over ids only, the ready systems with fewer than eight core types, in id order; each is
    filled as its owner by `Ontology.fillCore`, which adds only the missing keys. A second backend filling at once is
    refused by the core key and that system is filled at the next start; a failure is logged (`knowledge.core_types_failed`,
    ids only) and never blocks the start. `gmshield.knowledge.core-backfill.enabled=false` switches it off (the unit
    tests run it themselves). A read never writes.
  - `GET /api/v1/systems/{id}/record-types?limit&offset` (`listRecordTypes`) answers `{items, total}`: the core types in
    their fixed order, then the others oldest first; `limit` 1 to 50 (default 20) and `offset` 0 to 100000, else 400
    `request.invalid`. Each item: `id`, `name`, `core` (null for a type that is not core), `records` (from Story 4.2 the
    records of that core type in the system's accepted versions) and `version`. `GET /api/v1/record-types/{id}` (`getRecordType`) answers one.
  - `PATCH /api/v1/record-types/{id}` (`changeRecordType`, `{version, name}`) and `DELETE` (`deleteRecordType`, 204):
    a core type is 409 `ontology.core_record_type` and nothing changes. A type that is not core is renamed on its
    version (`request.stale_version`, 409), to 1 to 120 code points after trimming (`ontology.record_type_name_invalid`,
    400, params `min` and `max`) that no other type of the system has, core ones included
    (`ontology.record_type_name_taken`, 409), and removed (204; `request.stale_version`, 409, when a rename committed
    meanwhile; from Story 4.2 409 `ontology.record_type_in_use` for one that types records). Story 4.4 adds the
    approval that creates them (below).
  - Another owner's system or record type, an unknown or malformed id, and a system being deleted (or a record type of
    one) are 404 `request.not_found` on every route. `/api/v1/record-types` joins `LibraryOwnerFilter.LIBRARY_ROUTES`,
    and the four routes join the cross-owner audit (`CrossOwnerAuditFlowTest`, whose world now holds A's core record
    type).
  - A system's deletion: `service.SystemRecordTypesDeletion` (a `SystemDeletionHandler`, part `recordTypes`, counting 0)
    removes its record types before the system's own row; a campaign's, a material's, a version's or a conversation's
    deletion takes nothing here.
  - Log lines (ids, counts and versions only): `knowledge.core_types_seeded` (a new system's eight),
    `knowledge.core_types_filled` (the core types the fill added to one system), `knowledge.core_types_backfilled` (a
    start's fill: how many systems it added types to, how many failed), `knowledge.core_types_failed` (one system's fill
    failed; it is tried again at the next start), `knowledge.core_types_deferred` (the whole start fill stopped, for
    example when the list of systems could not be read: nothing more is filled on that start, the next start tries
    again), `knowledge.record_type_renamed`, `knowledge.record_type_deleted`, and `knowledge.deletion_removed` (a
    system's record types removed by its deletion).
  - Deploy order: the backend first, then the frontend (its minimum public contract becomes 1.33.0). The worker and the
    internal contract (1.16.0) do not change.

- **Extract rules, spells and items (`materials`, `knowledge`, `jobs`, Story 4.2 part A, internal contract 1.17.0,
  public contract 1.34.0, migration V35).** Records are not indexed as Qdrant points yet (part B).
  - `V35__record.sql`: `record (id, owner_id, version_id, ordinal, first_page, type, name, fields, created_at)`, `type`
    one of the eight core keys (`record_type_check`; this release extracts `rule`, `spell` and `item`), and
    `record_passage (owner_id, record_id, passage_id)`. Every reference carries the owner and none cascades: whatever
    replaces or removes a version's passages removes its records first, in the same transaction. `ingestion_step` gains
    `input_passages` (a records step, with a page range and no input file) and `records_unread` (a records step that
    failed for good). The previous release does not understand a records step: rolling back past V35 is safe only while
    none exists.
  - The chain: a report that carries passages may name next steps `{stage: "records", firstPage, lastPage, input:
    "passages"}` (the worker asks one per 10 pages that hold passages); the input `passages` belongs to that stage only
    (`ingestion.next_input_unknown`). Each gets its job at once, so the version needs review only once they have all
    ended. Its claim input names `input: "passages"` and `recordTypes: ["rule", "spell", "item"]` (from Story 4.4 part
    B, internal contract 1.21.0, followed by the keys approved in the material's systems, with `recordTypeNames`); `GET
    .../inputs/passages` streams the passages of its pages as JSON Lines `{id, page, sectionPath, text}`, decrypted in
    the input's read transaction. Its report carries `records` (at most 1,000), each a `type` of those, a non-blank
    `name` (at most 200 code points), `fields` (at most 64 `{name, value}`, each non-blank, at most 200 and 8,000 code
    points) and 1 to 100 distinct `passageIds`, each a passage of the version on the step's pages; anything else, or
    records from another step, or a records step without them, is 400 `job.result_invalid` (`ingestion.record_invalid`)
    and nothing is kept. The step's run of records is replaced (`ExtractedRecords.replaceRange`), so the same step
    reported again never adds to them; a record's first page is the lowest of its passages'.
  - A records step that ends failed (a refusal or truncation the worker reports with `retry: false`, an outage after
    its last attempt, a provider the worker does not hold) never fails the version: its pages are marked
    `records_unread`, and the chain still ends in `needs_review`. A pipeline change still restarts the chain.
  - Models: `JobHandler.callsModel(JobRef)` is per job; ingestion answers true for a records step only, so only its
    claim resolves a model and the job row records it. From internal contract 1.17.0 the outcome (`JobOutcome`) may
    carry `usage` (at most 16 `JobCallUsage`, checked as an events batch's, else 400 `request.invalid`), recorded once
    per worker call id for the attempt's model in the outcome's transaction, done or failed.
  - Storage (`knowledge`): `service.ExtractedRecordStore` implements the `materials` port
    `service.ExtractedRecords` (`replaceRange`, `removeVersion`, `countsByType`), so `materials` depends on nothing of
    `knowledge`; `knowledge` may now use `materials :: service`, whose named interface also holds `ExtractedRecords` and
    `ReadyVersions`. A record's name (entity `record`, field `name`) and its fields (field `fields`, a JSON array of
    `{name, value}` in order) are vault ciphertexts bound to the row and owner.
  - Removal: a passages report, a restart (`IngestionSteps.startOver`), a reprocess, a discard (`VersionRemoval`), a
    version's deletion (`SystemMaterialsDeletion`) and a refused upload (`Uploads.refuse`) remove the version's records
    before its passages; the first three also clear the unread marks. A reprocess re-mints every passage of the
    version, so it removes every record of the version, not only the reread pages', and the held passages step asks
    every records step again: one model call per 10-page run that holds a passage.
  - Reads: `GET /api/v1/materials/{id}/report` adds `records: [{type, count}]` (core order, types with at least one) and
    the warning `report.records_unread` (the unread pages). The records tab's counts (`records` of each core type) are
    the records of the `ready` versions of the materials attached to the system and of its campaigns' materials
    (`materials.service.ReadyVersions`): a version that needs review counts in its own report only.
  - Log lines (ids and counts only): `ingestion.passages_opened`, `ingestion.records_recorded` (the step's pages, its
    records by type, how many it replaced) and `ingestion.records_unread` (the failed step's code and page count).
  - Deploy order: the backend (internal 1.17.0, public 1.34.0), then the worker (its minimum backend contract becomes
    1.17.0; a records step under an older worker's pipeline restarts the chain), then the frontend.
- **Browse records (`knowledge`, Story 4.3 part A, public contract 1.35.0).** Read-only; no migration.
  `knowledge.service.Records` reads a system's records from its `ready` versions only (`ReadyVersions.ofSystem`, the
  records tab's rule); everything else (another owner's record, one of a version that needs review or is superseded,
  one of another system, a system being deleted, an unknown or malformed id) is 404 `request.not_found`, with no name
  or field in the answer.
  - `GET /api/v1/record-types/{id}/records?limit&offset` (`listRecords`) answers `{items: [{id, name, materialName,
    page}], total}`, alphabetical by name: the type's records are read (`RecordStore.ofTypeIn`), their names decrypted
    and sorted with a root-locale `java.text.Collator` at secondary strength (case ignored; accents order only names
    that are otherwise equal, the unaccented first), the record id breaking ties, then paged in memory (limit 1 to
    50, default 20; offset 0 to 100000). Two records with one name are two rows. A type that is not core has none yet.
  - `GET /api/v1/systems/{systemId}/records/{recordId}` (`getRecord`) answers `{id, name, recordType: {id, core,
    name}, fields: [{name, value}], references: [{versionId, materialName, sectionPath, page, passageIds}]}`: the
    system's record type of the record's core key (`core` is never null), the fields in stored order, and one
    reference per distinct (version, section path, page) of its passages, in page order. `passageIds` lists the
    record's passages at that place, in the order `RecordSources.places` gives them, so the source view marks them as
    it marks an answer's cited passages.
  - `.../records/{recordId}/pages/{page}` (`getRecordSourcePage`, the `MaterialPageView` shape) and `.../picture`
    (`getRecordSourcePagePicture`, WebP, `nosniff`) serve any page of the record's version: the source view's ground
    "a record as its system shows it".
  - `knowledge` may not use `ScopeReads` (AD-9): the new `materials.service.RecordSources` (named interface) wraps
    `ScopeReads.places`, the material names of ready versions and `versionPage`/`versionPicture` for it.
  - Log lines (ids and counts only): `knowledge.records_listed`, `knowledge.record_read`, `knowledge.record_page`,
    `knowledge.record_picture`; each says `found` or `served`, a miss included. The four routes are in the
    cross-owner audit.
  - Deploy order: backend → frontend.
- **Records in library search (`conversations`, `scope`, `knowledge`, Story 4.3 part B, public contract 1.36.0,
  internal contract 1.18.0, migration V37).** A campaign's search also returns the in-scope records, mixed with
  passages by the worker's rank, and a record whose name equals the query comes first.
  - **V37.** `record.name_fingerprint` (nullable): the vault's keyed fingerprint (entity `record`, field `name`) of
    the name's `Library.uniqueForm` (trimmed, NFC, lower case in the root locale), set on insert by
    `ExtractedRecordStore`, with an index on `(owner_id, name_fingerprint)`; a record stored before V37 has none and
    never matches by name (reprocessing its material makes a new version, whose records get one). `search.record_type` (null or a core key). `search_result.record_id`, with `passage_id`
    now nullable, a check that exactly one is set, and a unique key on `(owner_id, search_id, record_id)`. The
    previous release reads `passage_id` as never null: rolling back past V37 needs `delete from search_result where
    record_id is not null` first.
  - **Create.** `NewSearch` gains `recordType` (absent or null, else one of the eight core keys; anything else is 400
    `request.invalid`): only records of that type, no passage. It combines with `tier` and `materialId`. `Search`
    gains `recordType`.
  - **The job.** `Scopes.searchInput(scope, query, recordType)` adds `recordType` to the input when set (internal
    1.18.0). `completed` takes passage and record ids (still 0 to 20, distinct): `Scopes.records` reads the records
    among them within the narrowed scope (through the port `materials.service.ExtractedRecords.located`, which
    `knowledge` implements: `scope` depends on nothing in `knowledge`), the rest are checked as passages
    (`Scopes.references`). An id that is neither an allowed record nor an allowed passage, and with a record-type
    filter any passage or a record of another type, fails the search with `scope.passage_refused`. Then
    `Scopes.namedRecords` finds the narrowed scope's records whose fingerprint equals the query's
    (`ExtractedRecords.named`: an equality on a keyed fingerprint, never a text search, AD-19), of the filter's type
    when set, leaving out those in a records run that has not ended or failed (`ScopeReads.unindexedRecordRuns`), so a
    record is found by name only once the index can find it too (one the worker returned is kept whatever its run
    says). The stored order (`SearchJobs.ranked`): the
    exact-name records the worker returned, in its order; the other exact-name records, by name (root collation) and
    id; then every other id in the worker's order; at most 20.
  - **Read.** Each result has `kind` (`passage` or `record`). A passage keeps `passageId` and `firstWords`; a record
    has `recordId`, `name` (decrypted only for this answer), `recordType` (its core key) and `passageIds` (its
    passages on its first page, for the source view), with its first page, that page's first passage's section path,
    its material's name and its tier. Records are re-read within the current narrowed scope on every `GET`
    (`Scopes.records`), as passages are.
  - **A record as a campaign shows it.** `GET /api/v1/campaigns/{campaignId}/records/{recordId}`
    (`getCampaignRecord`, `OntologyController`, schema `Record`): `Records.inCampaign` answers part A's record for a
    record of a version that is an allowed source of the campaign's current scope (`Scopes.resolveSources` with its
    stored settings, no filter, any tier switched on), its record type the campaign's system's type of its core key. Anything else
    (a material switched off, a version that needs review or is superseded, another owner's, an unknown or malformed
    id, a campaign that is not the account's) is the same 404. Its references open on the scope's ground
    (`getScopeSourcePage`); there is no new page route. `knowledge` now depends on `scope :: service`. The route is in
    the cross-owner audit.
  - Log lines (ids, counts and core keys only): `search.created` gains `recordType`; `search.done` gains `records` and
    `exactNames`; `search.result_refused` gains `wrongType`; `scope.records_read` (`requested`, `records`, `notRecords`:
    the ids that are no allowed record, a search's passages among them), `scope.named_records_read` (with
    `unindexed`), `knowledge.campaign_record_read`.
  - Deploy order: backend → worker (its minimum backend contract becomes 1.18.0) → frontend. Until the worker is
    deployed, a search by name still puts the exact-name records first, and a search with a record-type filter fails
    with `scope.passage_refused` (the older worker returns passages).
- **Records in answers (`conversations`, `scope`, `jobs`, `knowledge`, Story 4.3 part C, public contract 1.37.0,
  internal contract 1.19.0, migration V38).** An answer job also reads the in-scope records the worker found, the model
  cites a record by its id as it cites a passage, and a cited record becomes a source reference of its own, stored by
  value with its name, type and fields.
  - **Read.** `POST /internal/v1/jobs/{jobId}/claims/{claimId}/records` (`readJobRecords`, `jobs`), with
    `X-Job-Grant`: body `{ids: [uuid]}`, 1 to 100 non-null ids. The checks are the passages read's, in the same order
    (`JobClaimReads`, shared by `JobPassageReads` and `JobRecordReads`): 400 `request.invalid` for a malformed body,
    403 `job.grant_refused`, 400 `job.records_invalid`, then the handler as the job's owner in a read-only
    transaction through `JobHandler.records(JobRef, ids)`, whose default is 403 `job.records_refused`. `AnswerJobs`
    answers through `Scopes.recordContents(frozen scope, ids)`: every id must be a record (`ExtractedRecords.located`)
    of an allowed source still `ready` with a passage there on its first page, else the whole batch is 403
    `scope.passage_refused` before anything is decrypted; then `ExtractedRecords.read` (new port method, implemented
    by `knowledge`) decrypts the names and fields. The answer is `{records: [{id, tier, type, name, fields: [{name,
    value}], page, sectionPath}]}` (`JobRecord`): its core type key, its first page and that page's first passage's
    section path. Never cached.
  - **Citations.** A `JobStreamEvent` `citation` names exactly one of `passageId` and `recordId` (else 400
    `job.events_invalid`); a stored citation keeps whichever it named (`TurnEvent.recordId`), and the GM's stream sends
    `{recordId, offset}` for a record (`TurnCitationEvent`: `passageId` is no longer required).
  - **References.** `AnswerJobs.storeReferences` splits the cited ids: passages go through `Scopes.references` as
    before; records through `Scopes.citedRecords`, one read with their fields in which an out-of-scope, unknown,
    foreign or since-deleted id is dropped, never refused (so the `done` or stop path always ends the turn). In
    first-citation order each record is a reference of its own
    (`TurnReferences.groupCited`: the grouping key includes the record id), numbered with the passage places: its
    version, material, first page, that page's first passage's section path, its passages on that page, its material's
    name and its tier. `conversation.references_stored` adds `citedRecords` and `droppedRecords`.
  - **V38.** `turn_reference.record_id uuid` and `record_type varchar(32)` (a core key, checked), plus `record_name`
    and `record_fields` (a JSON array of `{name, value}`) encrypted under the bindings `turn_reference`/`record_name`
    and `turn_reference`/`record_fields`; a check sets the four together (all null on a passage's reference).
    `record_id` keeps no foreign key: records are hard-deleted and re-minted on reprocessing, and the stored reference
    keeps its fields as they were when the answer was written (AD-11). Rolling back past V38: the previous release
    reads a record reference as a passage reference (no name, no fields), but a stored citation event naming a
    `recordId` and no `passageId` breaks its `TurnEvents.data`; once any record has been cited, the rollback needs
    those turns' record citation events removed first (or is unsafe).
  - **Read back.** `TurnSourceReference` gains `kind` (`passage` or `record`, always present) and, for a record,
    `recordId`, `recordType` (core key), `name` and `fields: [{name, value}]` (`TurnReferenceField`). A passage id
    appears in at most one passage reference of a turn; record references may share passage ids (two records read
    from one page share its passages), and a record's may also name a passage a passage reference names. `removed` is
    the version's state, as for passages. The reference page routes
    (`/api/v1/turns/{turnId}/references/{number}/pages/{page}` and its picture) serve a record reference as any other:
    the turn's ground, its version.
  - Log lines (ids and counts only): `job.records_read` (`requested`, `records`), `scope.record_contents_read`,
    `scope.cited_records_read` (`cited`, `kept`, `dropped`); a refusal is `request.refused` with its code. No name or
    field is logged.
  - Deploy order: backend → worker (its minimum backend contract becomes 1.19.0) → frontend (its minimum public
    contract becomes 1.37.0) → pipeline. An older worker never asks for records, so answers stay passage-only until it
    is deployed; an older frontend ignores `kind` and shows a record reference as a passage line.

- **Ontology proposals (`knowledge`, `materials`, Story 4.4 part A, public contract 1.38.0, internal contract 1.20.0,
  migration V39).** A records step's report may carry `proposals`: record types and terms its passages name, each a
  `kind` (`record_type` or `term`), a `name` (1 to 120 code points), a `definition` (at most 500, a term's quoted
  definition) and 1 to 3 `examples` (`passageId`, `quote` of at most 200); at most 20 per report, only from a records
  step (else 400 `job.result_invalid`, `ingestion.proposal_invalid`).
  - **Checked quotes.** `IngestionSteps.storeProposals` decrypts the cited passages of the step's pages and looks for
    each quote in its passage's text, both in NFC with whitespace collapsed (`Quotes`): an example not found is dropped,
    a proposal left with none is dropped, and so is a term whose definition is not in a cited passage, and a proposal
    whose name is in none of its kept quotes or its kept definition (case aside). U+0085 counts as whitespace, as in the
    worker. A system being deleted gets none. Log
    `ingestion.proposals_recorded` (counts only).
  - **Stored per system** (`materials.service.OntologyProposals`, implemented by `knowledge.service.Proposals`): one
    `proposal` row for each system the material counts under (its systems, or its campaign's). Dropped: a name the
    system has (a record type, a term, an alias, or a core type's name in pt-BR or en, compared by `Library.uniqueForm`
    through one fingerprint, `NameSpace`) and a name already proposed for the same system and material, whatever its
    state, so a rejected one is never raised again; a repeat within the same version adds its examples up to three, and
    a pending one of another version (a superseded one) moves to the new version with the new examples. An approved
    type's key never takes a core key (a suffix `_2` … `_999` follows), and its slug is at most 28 characters.
    Name, definition and examples are vault ciphertexts (entity `proposal`).
  - **Routes.** `GET /api/v1/systems/{id}/proposals` and `GET /api/v1/materials/{id}/proposals` (pending ones, oldest
    first, `{items, total}`, `limit`/`offset` as the record types); `POST /api/v1/proposals/{id}/approval` (`{version,
    name?}`: a record type with a stable `key`, the slug of the approved name, or a term with its quoted definition),
    `/merge` (`{version, targetId}`: a term proposal adds its name as an alias of a term of its system; a type proposal
    only closes into a type of its system; else 400 `ontology.merge_target_invalid`) and `/rejection` (`{version}`); `GET
    /api/v1/systems/{id}/terms`. A decision names the proposal's version (409 `request.stale_version` when it moved or
    was decided); a taken name is 409 `ontology.name_taken`, a name outside 1 to 120 code points 400
    `ontology.name_invalid`. Each runs with the system's ontology locked (`KnowledgeStore.lockOntology`). A record type
    now answers `key` and `origin` (`{kind: core|proposal, proposalId, materialId, materialName}`), and a rename checks
    the same name space. `/api/v1/proposals` joins `LIBRARY_ROUTES`; the six routes join the cross-owner audit.
  - **Clearing.** Whatever removes a version's records (a restart, a reprocess, a passages report) and a version's
    removal (a discard, a refused upload, a deletion) remove its pending proposals and detach its decided ones; a
    material's deletion removes its proposals that were not approved (an alias merged from one keeps its term) and
    detaches the approved ones, whose origin then has no material. A system's deletion removes its aliases, terms,
    record types and proposals, in that order.
  - **V39.** `proposal`, `term`, `term_alias`; `record_type.key` (backfilled from `core`; null in a core row the previous
    release writes, read as its core key) and `record_type.proposal_id` (null exactly for a core type). Part B extracts
    approved types.
  - Log lines (ids and counts only): `knowledge.proposal_approved`, `knowledge.proposal_merged`,
    `knowledge.proposal_rejected`. Deploy order: backend → worker (pipeline 9) → frontend (minimum public contract
    1.38.0).

- **Approved record types are extracted (`knowledge`, `materials`, `scope`, `conversations`, Story 4.4 part B, public
  contract 1.39.0, internal contract 1.21.0, migration V40).** A record stores its type's key: a core key, or the key of
  a type the GM approved. Every check that took only core keys takes the key pattern `^[a-z][a-z0-9_]{0,31}$`
  (`materials.service.RecordTypeKeys.KEY`).
  - **Extraction.** The port `materials.service.RecordTypeKeys` (implemented by `knowledge.service.RecordTypeKeyStore`)
    answers the types a records step extracts: `rule`, `spell` and `item`, then every key approved in the systems the
    material counts under, each once, named by the first system that has it. The claim input carries them in
    `recordTypes` and each key's name in `recordTypeNames` (`{key: name}`; the model reads the names as data). The other
    core types are never extracted. When the report is recorded, with the version locked, the keys are worked out
    again: a record whose key is no longer among them (its type was deleted, the material left its system) is dropped
    and counted (`dropped` on `ingestion.records_recorded`), never refused; a `type` that is not a key's text still
    refuses the report. The log line counts `rule`, `spell` and `item` by name and every approved key as `approved`.
  - **Per system.** A system lists, counts, opens, searches and cites a record only when it has a type with the
    record's key (`KnowledgeStore.findByKey`, which also replaces the read of the first eight types): in a system that
    never approved the type, the record stays passages. `ResolvedScope.recordTypes` holds the campaign's system's keys,
    and `Scopes` keeps only records of those keys in every read (`records`, `namedRecords`, `recordContents`,
    `citedRecords`); a worker that returns another refuses the search, and an answer's read of one is refused whole
    (`scope.passage_refused`). A question's and a `library.search` input carry those keys as `recordTypes` (sorted),
    and the worker filters the index's record points to them. A search's `recordType` filter takes any key.
  - **Contracts.** `RecordTypeOfRecord.core` is null for an approved type and gains `key`; record types in search
    results, references, the quality report (`ReportRecordCount`, the core types first, then the other keys
    alphabetically) and the worker's `JobRecord` are keys of the pattern. No contract field carries a type's name: a
    client names a key from the system's record types (the frontend shows the key when none matches). An approved name
    whose slug would start with a digit gets `t_` in front (`3d6 Tabela` → `t_3d6_tabela`).
  - **V40.** `record_type_check`, `search_record_type_check` and `turn_reference_record_type_check` become the key
    pattern. A rollback past V40 is safe: the previous release reads a record of another key as passages.
  - Deploy order: worker (it reads `recordTypeNames` and `recordTypes`), then frontend (it names any key; its minimum
    public contract stays 1.38.0), then backend. This is an exception to the usual backend-first order (part A was
    backend → worker → frontend): only the backend writes the new keys, so the apps that read them go first. The worker's and the frontend's minimum contracts stay where they are.

- **Correct a record (`knowledge`, `materials`, Story 4.5 part A, public contract 1.40.0, internal contract unchanged,
  migration V41).** The GM corrects a misread record's name, type and fields; the record keeps its id and its source
  references.
  - **Routes.** `PATCH /api/v1/systems/{systemId}/records/{recordId}` (`correctRecord`) and `PATCH
    /api/v1/campaigns/{campaignId}/records/{recordId}` (`correctCampaignRecord`) take `{version, name, recordType,
    fields: [{name, value}]}` and answer the record as `getRecord` does, its `version` raised by one. `Record` gains
    `version` (additive). Each route has the visibility of its read (a record of a ready version of the system, or of
    the campaign's current scope): anything else is 404 `request.not_found`. A stale version is 409
    `request.stale_version`; a blank or too long name is 400 `record.name_invalid` (`min`, `max`), more than 64 fields
    `record.fields_invalid` (`max`), a field's name or value outside its limits `record.field_name_invalid` or
    `record.field_value_invalid` (`min`, `max`, `index`), and a key that is not one of the page's system's record types
    (core or approved; the campaign's system on the campaign route) `record.type_invalid`. Values are trimmed. The
    limits are the extraction's, now shared in `materials.service.ExtractedRecords` (`MAX_NAME` 200, `MAX_FIELDS` 64,
    `MAX_FIELD_NAME` 200, `MAX_FIELD_VALUE` 8,000 code points). Both routes join the cross-owner audit.
  - **Storage.** A correction rewrites the record in place (`RecordStore.correct`, the row locked and its Hibernate
    `@Version` checked): its type key, name and fields (vault ciphertexts bound to the row), its name fingerprint
    worked out again, and `corrected_at`. The first correction keeps the type key and fingerprint the model produced as
    the extracted match key (`extracted_type`, `extracted_name_fingerprint`). No history of a record's corrections is
    kept; answers already stored keep the record by value.
  - **Reindex.** The port `materials.service.RecordIndexing` locks the record's version first (a correction never
    crosses the acceptance of a version that replaces it), requires `ready`, and adds an `index.change` records run of
    the record's first page (`[page, page]`) to the version's set, in the correction's transaction: the point keeps
    the record's id and gets the new text and `record_type`. The worker does not change; the version's recipe stays.
  - **Carry-over.** A version made by reprocessing an accepted one names it (`material_version.reprocessed_from`, set
    by `IngestionSteps.reprocessAccepted`). Its acceptance carries every correction of that version over
    (`ExtractedRecords.carryCorrections`, in the accept transaction, before `IndexChanges.start`): a new record whose
    (type key, name fingerprint) equals a correction's extracted or corrected key takes the corrected type, name and
    fields and keeps its own references (the nearest first page wins, then the lowest ordinal; a record takes one
    correction). A correction that matches none is copied in as a new record, its passages remapped by
    `materials.service.PassageRemap` to the new version's passages on its old pages (same page and section path first,
    otherwise every passage on the page; its first page is their lowest); one whose pages have no passage there is
    dropped and counted. A replacement upload names no version and carries nothing over.
  - **V41.** `record.version`, `corrected_at`, `extracted_type`, `extracted_name_fingerprint`;
    `material_version.reprocessed_from` (its version's deletion clears it). A rollback past V41 is safe: the previous
    release neither reads nor writes these columns, and reads a corrected record as its own.
  - Log lines (ids and counts only): `knowledge.record_corrected`, `knowledge.campaign_record_corrected`,
    `index.records_page_started`, `material.corrections_carried` (`matched`, `copied`, `dropped`). Deploy order:
    backend, then frontend (minimum public contract 1.40.0); the old frontend keeps reading records, since `version` is
    additive.

- **Delete a record (`knowledge`, `materials`, `scope`, `conversations`, Story 4.5 part B, public contract 1.41.0,
  internal contract 1.22.0, migration V42).** The GM deletes a record that should not exist (a misread entry, a
  duplicate, text the model took for a record). There is no undo.
  - **Routes.** `DELETE /api/v1/systems/{systemId}/records/{recordId}` (`deleteRecord`) and `DELETE
    /api/v1/campaigns/{campaignId}/records/{recordId}` (`deleteCampaignRecord`) answer 204 (no body, `no-store`) under
    the visibility of the record's read and correction: anything else is 404 `request.not_found`. No version is sent.
    Both routes join the cross-owner audit.
  - **Tombstone.** The row stays (`RecordStore.delete`, the row locked after the version's lock): `deleted_at` is set
    and its name and fields stay encrypted, never read again. Every public read leaves it out (`find`, `ofTypeIn`,
    `countsByType`, `named`, so the type list, the counts, both record pages, PATCH, the exact-name search and the
    record type's in-use check), and so do the index inputs (`ofVersion`, behind `firstPages` and `readRange`: version
    runs, page runs and reconciles), so its point is never written again. `findAll` takes a `Reach`: `LIVE` or `ALL`.
  - **Point removal.** The delete creates a job of the new kind `index.record_removal` (`materials.service.
    RecordRemovals`, through `RecordIndexing.removePoint`) in the same transaction. Its input row is
    `record_removal` (job, owner, version, record; no reference, so it outlives both) and its claim input is `{owner,
    source, ids}`. The worker deletes those points by id within the owner's source; the completion sets
    `point_removed_at` (`ExtractedRecords.pointsRemoved`). A failed attempt is offered again, and a job that failed
    after its attempts gets a new one from the `IndexRemovalRetrySweep`, which now also sweeps this kind. A new kind
    rather than ids on `index.removal`: an older worker would ignore `ids` and delete the whole source.
  - **Internal reads.** An answer's records read (`Scopes.recordContents`) still serves a deleted record whatever its
    point (`Reach.ALL`), so an answer whose search found its point (just before the removal ended, or a point written
    back later) does not fail; its stored citations drop it
    (`Scopes.citedRecords` reads live records only). A library search's completion drops the owner's deleted record
    ids it could not place as records (`Scopes.deletedRecords`) before it checks the passages, instead of failing. Stored answer turns are not
    changed: they keep the record by value.
  - **Carry-over.** At the acceptance of a version reprocessing made, after the corrections, each tombstone of the
    replaced version matches a new record no correction took, by its extracted key first (its own key when it was never
    corrected), then by its own key, the nearest first page winning, then the nearest ordinal: the new record is deleted
    too, its point counted as
    removed at once (it was never indexed). A tombstone that matches nothing is copied in with its keys, no passage and
    an empty name, so a later reprocess still finds it. `material.corrections_carried` gains `deleted` and
    `tombstoned`.
  - **V42.** `record.deleted_at`, `record.point_removed_at` (a check: it needs `deleted_at`), and the table
    `record_removal`. No partial index: every read already goes through the owner and version or id keys. Rolling back
    past V42 is not safe while a tombstone exists (the previous release reads it as a live record and cannot run
    `index.record_removal`): first wait until every tombstone has `point_removed_at` (or remove its version's points
    from the index) and end any waiting or running `index.record_removal` job, then remove the tombstone rows (links
    first) and the `record_removal` rows.
  - Log lines (ids and counts only): `knowledge.record_deleted`, `knowledge.campaign_record_deleted`,
    `index.record_removal_created`, `index.record_removal_done` (`marked`), `index.record_removal_failed`,
    `index.record_removal_retried`, `scope.deleted_records_read`, and `deletedRecords` on `search.done`. Deploy order:
    worker (it runs the new kind), then backend, then frontend (minimum public contract 1.41.0). Until the backend
    creates the new kind, a new worker changes nothing.

- **Questions understand the system's terms (`scope`, `knowledge`, `conversations`, `jobs`, Story 4.6, internal
  contract 1.23.0, no migration).** Before it searches, an answer's worker asks the backend to resolve the question's
  terms; the backend reads the turn's question itself, so the worker sends no text back.
  - **Route.** `POST /internal/v1/jobs/{jobId}/claims/{claimId}/terms` (`resolveJobTerms`, `JobTermController`) has no
    body and answers `{recordTypes, names, records}` (`no-store`) under the worker's credential and the live claim's
    grant (`job.grant_refused`, 403, also once the job's campaign is deleted, which ends its claim).
    `JobHandler.terms` refuses by default (`job.terms_refused`, 403): only `question.answer` (`AnswerJobs.terms`)
    resolves, within the turn's frozen scope. Lines: `job.terms_read` and
    `scope.terms_resolved` (counts only).
  - **Matching (`Scopes.resolveTerms`).** The question's spans of 1 to 6 words (`TermSpans`: the question in NFC, words
    split between white space by a loop over code points, joined by one space, each as written and without the
    punctuation at its end, at its start or both, in `Library.uniqueForm`, at most 200 code points, with a letter or
    digit; at most `TermSpans.MAX_SPANS` = 1,000 per question, in its order) are fingerprinted with the existing keyed HMACs and compared by
    equality only (fingerprint-IN queries of at most 500 fingerprints each): the `record_type` subkey against the
    system's record types, terms and aliases (`KnowledgeStore.recordTypesNamed`, `OntologyStore.termsNamed`,
    `aliasesNamed`), and the `record` subkey against the live records of the scope's allowed sources and type keys
    (`RecordStore.namedAny`). The core types also match their pt-BR and en names, singular and plural
    (`CoreRecordTypes.keyAsked`, a fixed list: "Quais magias…" names `spell`); nothing else is folded. The ontology and
    record matches come through the new port `materials.service.TermMatches`, implemented in `knowledge`
    (`TermMatchStore`).
  - **Answer.** `recordTypes`: the matched keys the campaign's system has, sorted. `names`: each matched term's name and
    all its aliases, then the matched records' names, each once (decrypted for the matched rows only; a definition is
    never read). `records`: the matched records placed within the scope as every scoped read places them, campaign tier
    first, then by id, at most `Scopes.MAX_TERM_RECORDS` (100); a deleted record or one of a material switched off, in
    review, of another owner or of a type the system lacks is never named.
  - Deploy order: the backend, then the worker (an older worker never calls the route; a new worker against an older
    backend gets a 404 and searches as before). The public contract does not change.

- **Write an artifact by hand (`artifacts`, `knowledge`, Story 5.1 part A, public contract 1.42.0, internal contract
  unchanged, migration V43).** The GM writes and keeps an artifact in a campaign: any core or approved record type of
  the campaign's system, a name, fields and an optional free text ("Conteúdo").
  - **Module.** The new module `artifacts` (it may use `shared`, `vault`, `library` and `knowledge :: service`;
    classes only in `web`, `service` and `data`) owns an artifact's content. `knowledge` owns its identity and type:
    `knowledge.service.ArtifactRecords` stores, lists, finds and deletes the `record` row of an artifact (its campaign
    instead of a material version) and checks what the GM wrote against the extraction's limits
    (`ExtractedRecords.MAX_NAME` and the others) and the type against the system's record types (`findByKey`, core or
    approved).
  - **Routes.** `POST /api/v1/campaigns/{campaignId}/artifacts` (`createArtifact`, body `{recordType, name,
    fields:[{name,value}], content?}`) answers 201 with `Artifact {id, campaignId, recordType, name, fields, content,
    version, createdAt}` (version 1); `GET` on the same path (`listArtifacts`, `?recordType&cursor&limit&offset`)
    answers `{items, nextCursor, total, types:[{key,count}]}`, newest first by id (UUIDv7), `types` being the record
    types in use in the whole campaign whatever the filter, and `offset` and `total` serving the frontend's numbered
    pages as the other lists do; `GET .../artifacts/{artifactId}` (`getArtifact`) answers one artifact. All `no-store`.
    A value outside its limits is `request.invalid` (400) with `params.field` (`recordType`, `name` 1 to 200 code
    points, `fields` at most 64, `fieldName` or `fieldValue` with `index`, 1 to 200 and 1 to 8,000, `content` at most
    20,000, all after trimming) and its limits; nothing is stored then. Another owner's campaign or artifact, another
    campaign's artifact, a malformed id and a campaign being deleted are `request.not_found` (404). The three routes
    join the cross-owner audit. A character sheet stores what was written: nothing is calculated.
  - **Storage.** One transaction writes the `record` row (`campaign_id`, the type key, no version, ordinal, first page,
    name or fields) and `record_version` number 1: the name, the fields (a JSON array) and the content (null for
    none), each encrypted bound to the entity `record_version`, its field, the artifact's id and the owner.
  - **Records stay extracted.** Every query of `JpaRecordStore` for a system's records goes through a material version
    (`versionId = :version` or `in :versions`) or, for a read by id (`find`, `findAll`, `versionOf`, the correction's and
    the delete's lock, `pointsRemoved`), requires one (`versionId is not null`), so an artifact never shows in a record
    type's list or count, a record page, a correction or delete, a library search, an answer's records or an index
    input. `ArtifactsIntegrationTest.noReadOfTheSystemsRecordsAnswersAnArtifact` proves it on PostgreSQL (the in-memory
    store keeps artifacts apart, so the flow test cannot).
  - **Deletion.** A campaign's or a system's deletion removes its artifacts, versions first
    (`artifacts.service.ArtifactsDeletion`, a `SystemDeletionHandler` of part `artifacts`). Both dialogs count them:
    `SystemDeletion` (`GET /api/v1/systems/{id}/deletion`) and `CampaignDeletion` (`GET /api/v1/campaigns/{id}/deletion`)
    gain `artifacts` (additive, contract 1.42.0). From Story 5.2 part B the GM deletes an artifact (below).
  - **Type in use.** `Ontology.delete` also refuses `ontology.record_type_in_use` (409) for an approved type that an
    artifact of the system's campaigns has (`RecordStore.artifactsOfTypeIn`).
  - **V43.** `record.version_id`, `ordinal`, `first_page`, `name` and `fields` become nullable; `record.campaign_id`
    (key on owner and campaign) with checks that exactly one of version and campaign is set and that an extracted
    record keeps its values; indexes on (owner, campaign, type) for the type filter and counts and on (owner,
    campaign, id) for the newest-first list; the table `record_version` (key on record and number, reference on owner
    and record). Rolling back past V43 is safe (only additions and relaxed NOT NULLs): the previous release never reads
    an artifact row. But its campaign and system deletions fail on the campaign reference while a campaign going has
    artifacts, and its `Ontology.delete` would remove an approved type that artifacts use, so delete the artifacts first
    (`delete from record_version;` then `delete from record where campaign_id is not null;`).
  - Log lines (ids and counts only): `artifacts.created`, `artifacts.listed`, `artifacts.read`,
    `artifacts.deletion_removed`. Deploy order: backend, then frontend (minimum public contract 1.42.0); the previous
    frontend keeps working, since the change is additive.

- **Artifacts answer questions (`artifacts`, `scope`, `conversations`, `knowledge`, Story 5.1 part B, public contract
  1.43.0, internal contract 1.24.0, migration V44).** A saved artifact is indexed, joins the campaign tier of its
  campaign's questions, and an answer that uses one cites it by value under "Campanha".
  - **Module.** `artifacts` may now use `jobs :: service` and `scope :: service` too.
  - **Index job.** `Artifacts.create` creates, in the save's transaction, one `index.artifact` job on the ingestion lane
    (`artifacts.service.ArtifactIndexing`, modelled on `IndexChanges`): an `artifact_index` row keyed by the job names
    the version (record and number) and records the recipe its result reports. Its claim input is a records run's,
    `{source: artifactId, owner, kind: "artifact", language: null, tokenKey, records}`, and its input `records` one JSON
    line `{id, page: 1, type, name, fields}`, the content (when there is one) as a last field named `content`. A bad
    recipe is `index.recipe_invalid` (`job.result_invalid`). A failed job is retried with a new one, the row moved to it,
    at most 3 times (`ArtifactIndexRetrySweep`, on the materials' index retry schedule and switch). A failed
    `index.artifact_removal` job is retried the same way with no cap, as the materials' removals are: its row moves to
    the new job, the same artifact and owner.
  - **Backfill.** On `IndexRecipeStated`, `ArtifactIndexReconcile` (beside `IndexReconcile`, which is untouched) visits
    each owner with a newest artifact version that no row reports under that recipe (`StaleArtifacts`, owners only, one
    JDBC statement) as that owner, and gives each such version of a readable campaign with no unended job a new job,
    with the version locked. The artifacts saved under part A are indexed the first time the worker states its recipe.
  - **Scope.** `scope.service.CampaignArtifacts` is a port that `artifacts` implements (`ArtifactSources`): a campaign's
    artifact ids, and artifacts by id with their newest version. `ResolvedScope.artifacts()` holds the campaign's
    artifact ids when the campaign tier is on, never among `allowedSources()`. `Scopes.questionInput` lists them after
    the campaign tier's sources; `searchInput` never does (a search places every hit as a material's), nor does any
    list, name or term read. `recordContents` (`POST …/records`) and `citedRecords` read an id of that set through the
    port as a campaign-tier record (page 1, `sectionPath []`; the job adds its content as the last field, named
    `content`), when its type is a key of the campaign's system; any other id is refused or dropped as before. A
    campaign whose only campaign-tier sources are artifacts keeps its `campaign` tier event (`ResolvedScope.hasSources`)
    and has sources for the GM's notice. An artifact is found by a question only once its index job has ended.
  - **Citation.** A cited artifact is a reference of its own, numbered with the others, stored by value in
    `turn_reference` with `artifact_id` and `artifact_version`, its type, name and fields (encrypted as a record's),
    never its content, and the tier `campaign`; no material column. The `done` event's reference has
    `kind: "artifact"`, `artifactId`, `artifactVersion`, `recordType`, `name`, `fields`, `removed` (false until Story
    5.2) and `tier`, and none of `materialName`, `sectionPath`, `page` and `passageIds` (now optional in
    `TurnSourceReference`). Its page and picture routes answer `request.not_found`.
  - **Deletion.** A campaign's or a system's deletion first locks and cancels its artifacts' unended index jobs and
    waits for their claims (`ArtifactsDeletion.prepare`), then, in one transaction, creates one `index.artifact_removal`
    job per artifact (`ArtifactIndexRemovals`, input `{source: artifactId, owner}`, the worker's `index.removal` logic;
    its `artifact_index_removal` row has no reference to the artifact) and removes the `artifact_index` rows, the
    versions and the identities.
  - **V44.** The tables `artifact_index` (job key, owner, record and number, recipe, retries, references on (owner,
    record) and (record, number)) and `artifact_index_removal`; `turn_reference`'s material columns become nullable,
    `artifact_id` and `artifact_version` are added, and `turn_reference_record_check` now holds a passage's, a record's
    and an artifact's row. Rolling back past V44 is safe until an artifact is cited: then delete those references first
    (`delete from turn_reference where artifact_id is not null`). In any case end the jobs of the two new kinds, whose
    handlers the previous release does not have, as `Jobs.cancel` ends a job, with the backend stopped, as
    `gmshield_backend`:

    ```sql
    update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null,
                   outcome_code = null, updated_at = now()
     where kind in ('index.artifact', 'index.artifact_removal') and state in ('waiting', 'offered', 'running');
    ```

  - Log lines (ids and counts only): `artifacts.index_created`, `artifacts.index_opened`, `artifacts.index_done`,
    `artifacts.index_failed`, `artifacts.index_retried`, `artifacts.index_reconciled`, `artifacts.reconciled`,
    `artifacts.reconcile_failed`, `artifacts.reconcile_owner_failed`, `artifacts.index_removal_created`,
    `artifacts.index_removal_done`, `artifacts.index_removal_failed`, `artifacts.index_removal_retried`,
    `artifacts.index_retry_swept`, `artifacts.index_retry_sweep_failed`, `artifacts.index_retry_owner_failed`,
    `artifacts.deletion_prepared`, and `artifacts` on `scope.resolved` and `scope.question_input`. Deploy order: worker (it runs the two new kinds), then backend, then
    frontend (minimum public contract 1.43.0). An older frontend skips an artifact's reference.

- **Edit an artifact and keep its versions (`artifacts`, `knowledge`, Story 5.2 part A, public contract 1.44.0,
  internal contract unchanged, migration V45).** The GM edits an artifact in place, every earlier version is kept, and
  an earlier version can be restored as the newest; nothing is deleted.
  - **Routes.** `PATCH /api/v1/campaigns/{campaignId}/artifacts/{artifactId}` (`updateArtifact`, body `{version,
    recordType, name, fields, content?}`) and `POST …/artifacts/{artifactId}/versions/{number}/restore`
    (`restoreArtifactVersion`, body `{version}`) each answer 200 with the new version as an `Artifact`; `GET
    …/artifacts/{artifactId}/versions` (`listArtifactVersions`, `?cursor&offset&limit`) answers `{items, nextCursor,
    total}`, newest first, each item `{number, recordType, name, createdAt}` (the cursor is the last number shown); `GET
    …/artifacts/{artifactId}/versions/{number}` (`getArtifactVersion`) answers that version as an `Artifact`, as a
    reference that cited it opens it. `Artifact` gains `currentVersion`, the newest number (equal to `version` unless an
    earlier version is read). All `no-store`. The four routes join the cross-owner audit.
  - **Checks.** An edit keeps create's limits and type check (`ArtifactRecords.checked`/`checkedType`): `request.invalid`
    (400) naming the field, and nothing is stored. A restore of a version whose record type the system no longer has is
    `request.invalid` with `params.field` `recordType`. `version` names the newest version the GM saw: any other number,
    or a number another write took first (the key on record and number, the store's `VersionTaken`), is
    `request.stale_version` (409). An edit that changes nothing (the same type, name, fields and content as the newest
    version it names) answers that version and stores no version and no index job. Another owner's or another
    campaign's artifact, and a version number the artifact does not have (or a malformed one), are
    `request.not_found` (404).
  - **Write.** In one transaction the edit or restore locks the artifact's unended index jobs, then its newest version
    (the order a job's completion takes them), reads the newest number again, inserts the next version (its own record
    type, name, fields and content, encrypted as version 1's), gives the identity the new type
    (`ArtifactRecords.retype`, `RecordStore.retypeArtifact`, the row locked; never the extracted records' correction) and
    creates the new version's `index.artifact` job. `record.type` therefore always mirrors the newest version, and every
    reader (the artifacts tab, "Personagens", scope, the type-in-use guard) keeps reading it.
  - **Index order.** `ArtifactIndexing.start` cancels the unended jobs of the artifact's older versions before it
    creates the new one, since the worker keeps one point per artifact; `retryFailed` retries only a row of the newest
    version, and `reconcile` already reads only the newest.
  - **V45.** `record_version.record_type`, filled from each version's record, then NOT NULL, with V40's key check.
    Rolling back past V45 is safe once the NOT NULL is dropped (`alter table record_version alter column record_type
    drop not null;`), since the previous release inserts versions without it; the migration's comment gives the
    statements that fill those rows before this release is deployed again.
  - Log lines (ids and counts only): `artifacts.updated`, `artifacts.restored`, `artifacts.versions_listed`,
    `artifacts.index_superseded`, and `version` on `artifacts.read`. Deploy order: backend, then frontend (minimum
    public contract 1.44.0); the previous frontend keeps working, since the change is additive.

- **Delete an artifact (`artifacts`, `knowledge`, `library`, `scope`, `conversations`, Story 5.2 part B, public contract
  1.45.0, internal contract unchanged, migration V46, AD-22).** The GM deletes an artifact with every version; it is
  the recorded, resumable deletion of `SystemDeletion`, of the new kind `artifact`.
  - **Route.** `DELETE /api/v1/campaigns/{campaignId}/artifacts/{artifactId}` (`deleteArtifact`) answers 202 with no
    body, `no-store`. Another owner's or another campaign's artifact, an unknown or malformed id and an artifact already
    being deleted are `request.not_found` (404). The route joins the cross-owner audit.
  - **The GM's transaction** (`Artifacts.delete`) finds the live artifact, locks its unended index jobs, then its newest
    version (the order an edit takes), marks its `record` row deleting (`record.deleted_at`,
    `ArtifactRecords.markDeleting`, `RecordStore.markArtifactDeleting`, the row locked) and records the deletion
    (`SystemDeletion.deleteArtifact`, `Going.ofArtifact`), whose `ArtifactsDeletion` prepare cancels those jobs and
    answers their latest claim lapse. The one-shot pass is scheduled once that transaction commits.
  - **Hidden at once.** Every artifact read leaves a deleting artifact out: its page, a version, the versions list, an
    edit and a restore (404), the list and its type counts ("Personagens" included), the campaign tier of a question's
    scope, the index backfill and retries, the type-in-use guard and the counts of the campaign and system deletion
    dialogs.
    Only `ArtifactRecords.allOf`/`allIdsOf` (`RecordStore.Reach.ALL`) read it, for a deletion's removal, so a campaign's
    or a system's deletion still removes a deleting artifact.
  - **The pass** waits for any live claim of a cancelled job, then, in one transaction, creates one
    `index.artifact_removal` job and deletes the artifact's `artifact_index` rows, its versions and its row. A restart
    before the pass leaves the record to the sweep.
  - **Answers.** An answer running when the artifact is deleted leaves it out of its records read and its citations
    instead of failing (`Scopes.recordContents`/`citedRecords`; each job call resolves the turn's scope again, and
    `CampaignArtifacts.deleted` names a deleted artifact's id from its recorded deletion). The internal records read
    (`readJobRecords`) then answers 200 with fewer records than asked instead of 403; its description says so, and the
    worker fails that attempt for a retry, whose scope no longer holds the artifact (internal contract version
    unchanged, `openapi/internal.json` regenerated). A stored artifact reference is `removed`
    when its artifact is deleting or gone, worked out when it is read (`Scopes.removedArtifacts`, through the
    `CampaignArtifacts.live` port); nothing is stored. A material's deletion touches no artifact row, version or
    reference.
  - **V46.** `deletion_kind_check` allows `artifact`. Before rolling back past V46, let every pending artifact deletion
    finish (the migration's comment gives the checks and the statement that restores the previous check).
  - Log lines (ids and counts only): `artifacts.delete`, `library.artifact_deleting`, `library.artifact_deleted`, and
    `artifactId` on `artifacts.deletion_prepared` and `artifacts.deletion_removed`. Deploy order: backend, then
    frontend (minimum public contract 1.45.0); the previous frontend keeps working, since the change is additive (its
    link to a deleted artifact opens "not found").

- **Draft content with an agent (`conversations`, Story 5.3 part A, public contract 1.46.0, internal contract 1.25.0,
  migration V47).** In the questions area the GM asks either a question or a draft (an encounter, an item, a creature,
  a character sheet...) written from the campaign's own sources. Nothing is written to the library; the proposal and
  the save confirmation are later parts.
  - **Route.** `NewTurn.kind` on `POST /api/v1/campaigns/{campaignId}/turns` is `question` (absent or null: the
    default) or `draft`. A kind that is not one, or a draft outside the questions area (by `area`, or by the
    conversation's area), is `request.invalid` (400) with the field `kind`, and nothing is recorded.
    `StoredTurn.kind` says what each stored turn is.
  - **The turn.** `turn.kind` (V47, `question` or `draft`, default `question`) is written once with the turn. A draft
    gets a job of kind `content.draft` (`DraftJobs`) on the interactive lane instead of `question.answer`. A draft is
    a turn, so stop, dismissal, bookmarks, the history sent with later turns, deletion and the cross-owner audit treat
    it as an answer, and it is never a source.
  - **The job.** `DraftJobs` is `AnswerJobs` under another kind (it delegates every call): the same input (the question
    input within the turn's frozen scope, campaign tier first, with the history), the same passages, records and terms
    reads, the same event batches, tier rules, references, outcome and stop. The worker tells a draft from an answer by
    the kind alone; `JobClaimController` documents it (`openapi/internal.json` 1.25.0).
  - **V47.** Additive: the previous release neither reads nor writes `turn.kind`. Before rolling back past V47, cancel
    every unended `content.draft` job (the previous release has no handler for it); the migration's comment gives the
    statement that drops the column.
  - Log lines (ids and counts only): `kind` on `conversation.turn_recorded`. Deploy order: backend, then worker
    (internal contract 1.25.0), then frontend (minimum public contract 1.46.0); the previous frontend and worker keep
    working, since questions are unchanged and no draft is asked until the new frontend sends one.

- **The draft's proposal (`conversations`, Story 5.3 part B, public contract 1.47.0, internal contract 1.26.0,
  migration V48).** A finished draft says what it is: the worker proposes a name and a record type for it, which Story
  5.4's save confirmation will suggest. Nothing is written to the library.
  - **The claim.** A `content.draft` input adds `recordTypeNames`: each `recordTypes` key with the name of the campaign
    system's record type (`Scopes.recordTypeNames`, through `RecordTypeKeys.named`: a core type by its stored English
    name, an approved one decrypted), data for the model.
  - **The result.** `DraftJobs.completed` reads `result.proposal {name, recordType}`. A missing result or proposal is
    accepted with none, so part A's worker keeps working. A proposal that is not an object, a name that is not a string or
    that, once trimmed, is blank or longer than 120 code points, or a `recordType` that is neither a string nor null is 400
    `job.result_invalid`, and nothing changes. A `recordType` that is not a key of the campaign's system when the draft
    ends (`Scopes.recordTypeKeys`) is stored as null.
  - **Stored before `done`.** Only when the turn has not ended and was not stopped, `markProposed` (one bulk update)
    stores the name trimmed and encrypted (binding `turn`/`proposal_name`) and the type plain, in the transaction that
    appends `done` (`AnswerJobs.completed` runs it before the references and `done`), so no reader sees a done draft
    without its proposal. An answer's result is never read.
  - **Exposed.** `TurnDoneEvent.proposal {name, recordType|null}` on the stream's `done` and in the stored turns, only
    when one is stored and the turn is neither stopped nor silent (worked out when `done` is read).
  - **V48.** Nullable `turn.proposal_name` (bytea) and `turn.proposal_type` (varchar(32)), the type set only with a name
    (`turn_proposal_type_check`). Additive: the previous release neither reads nor writes them; the migration's comment
    gives the statement that drops them.
  - Log lines (ids and counts only, a core type key or `approved`): `conversation.proposal_stored` (`recordType`,
    `typeDropped`, `nameLength`), never the name. Deploy order: backend, then worker (internal contract 1.26.0; its
    minimum stays 1.20.0); the 1.46.0 frontend ignores `proposal` until Story 5.4.

- **Save a draft through the save confirmation (`artifacts`, `conversations`, Story 5.4 part A, public contract
  1.48.0, migration V49).** A finished draft becomes an artifact only on the GM's "Salvar".
  - **Offered.** A draft turn that is done, not stopped and not silent (the bookmark rule plus `kind = draft`). Its
    `StoredTurn.saving {state: offered|declined|saved, artifactId|null}` is worked out when read: saved while
    `turn.saved_record_id` names an artifact live in the campaign (`Scopes.removedArtifacts`), declined once declined or
    once that artifact is deleting or gone, offered otherwise; absent on every other turn.
  - **Saving.** `POST /api/v1/turns/{turnId}/artifact` (`DraftSaveController`, body `SavedDraft {name, recordType,
    content?}`, 201 with the `Artifact`). `Artifacts.saveDraft` runs one transaction: `Conversations.saveable` (404,
    409 `conversation.turn_not_saveable`, 409 `conversation.turn_saved`), the artifact created as `Artifacts.create`
    does (its limits and refusals, no fields; the content the draft's joined deltas unless sent) with
    `record.conversation_id`, its index job, the draft's references copied by value into `artifact_reference`, sealed
    again under the artifact (binding `artifact_reference`/`<field>:<number>`), then `Conversations.markSaved`, a
    conditional bulk update (409 `conversation.turn_saved` when another save got there first, rolling everything back).
    The turn's events never change. `artifacts` depends on `conversations :: service`, never the other way
    (`ModuleBoundaryTest`).
  - **Declining.** `PUT /api/v1/turns/{turnId}/save-decline` ("Agora não") and `DELETE` ("Salvar como artefato"), 204
    and safe to repeat; 409 for a turn not offered or saved. A reopen also lets go of a deleted saved artifact.
  - **The artifact.** `Artifact.conversationId|null` and `references` (the `TurnSourceReference` shape, `removed` worked
    out when read: `Scopes.removedVersions` for a passage's or a record's, `Scopes.removedArtifacts` for an artifact's);
    kept by every edit and restore, empty for one written by hand.
  - **V49.** `record.conversation_id` and `turn.saved_record_id`, each `ON DELETE SET NULL` on that column alone (a
    conversation's deletion keeps its artifacts; an artifact's pass frees its turn), `turn.save_declined_at` (set by a
    decline and by a save, so a turn whose artifact is gone reads declined), and `artifact_reference` (cascade-deleted
    with its record; the deletion pass deletes it first). Additive; the migration's comment gives the drop statements.
  - Log lines (ids and counts only): `artifacts.draft_saved` (`edited`, `references`), `conversation.save_declined`,
    `conversation.save_reopened`. Deploy order: backend, then frontend (minimum public contract 1.48.0); internal
    contract and worker unchanged.

- **Delete a conversation that has saved artifacts (`conversations`, `artifacts`, Story 5.4 part B, public contract
  1.49.0, no migration).** A conversation's deletion names the artifacts saved from it, and the GM may take some.
  - **The dialog read.** `GET /api/v1/conversations/{id}/deletion` gains `artifacts: [{id, name, recordType}]`
    (`ConversationDeletionArtifact`): the conversation's live saved artifacts (`record.conversation_id`, not deleting),
    oldest first, possibly none. `conversations` reads them through its own port `ConversationArtifacts`, which
    `artifacts` implements (`SavedArtifacts`, over `ArtifactRecords.ofConversation` and V49's
    `record_owner_conversation` index); `conversations` never depends on `artifacts` (`ModuleBoundaryTest`).
  - **The delete.** `POST /api/v1/conversations/{id}/deletion` (body `ConversationDeletionRequest {artifactIds}`, 0 to
    64 UUIDs; 202, no body). `Conversations.delete(id, artifactIds)` runs one transaction: the conversation found (else
    404, nothing deleted), `SystemDeletion.deleteConversation` (a deletion recorded already is 404, and no artifact is
    touched), then each ticked id that is one of its live saved artifacts deleted as `Artifacts.delete` does (5.2 B's
    recorded deletion), any other id ignored. A malformed body, a malformed id or more than 64 ids is 400
    `request.invalid` with `params.field: artifactIds`. `DELETE /api/v1/conversations/{id}` stays and keeps every
    artifact.
  - **The link.** `Artifact.conversationId` is null unless that conversation is `ready` (`Conversations.ready`), so a
    deleting conversation's id never leaves, before its pass sets `record.conversation_id` to null.
  - Log lines (ids and counts only): `conversation.delete` gains `artifactsTicked`; each artifact taken logs
    `artifacts.delete`. Deploy order: backend, then frontend (minimum public contract 1.49.0); the 1.48.0 frontend's
    `DELETE` keeps the artifacts as before. Internal contract and worker unchanged.

- **Override a system record (`knowledge`, `artifacts`, `scope`, Story 5.5 part A, FR-20, public contract 1.50.0,
  internal contract 1.27.0).** An artifact can replace one system record in its own campaign.
  - **V50 `record_relation`** (owned by `knowledge`): `id`, `owner_id`, `from_record_id` (the artifact),
    `to_record_id` (the record), `kind` (check `overrides`, part B extends it), `created_at`; unique
    `(from_record_id, kind)`, both keys `on delete cascade` (the artifact's pass, a version's removal, a campaign's or
    a system's deletion take the marks). Additive; drop the table to roll back.
  - **The rule.** `knowledge.service.RecordRelations`: a mark applies in campaign C only from a live artifact of C to a
    live record of the system tier of C's resolved scope (stored settings). A second artifact of C on the same record
    is 409 `artifact.override_taken` (`params.artifactId`); anything that is no target (another system's, a campaign
    material's, a tombstone, another owner's, unknown, malformed) is 400 `artifact.override_target`. Both rows are
    locked first. The mark is not versioned (an edit or restore keeps it).
  - **API.** `PUT /api/v1/campaigns/{campaignId}/artifacts/{artifactId}/override` `{recordId}` answers the artifact
    (replacing a mark is allowed); `DELETE …/override` is 204 and idempotent; `GET /api/v1/campaigns/{campaignId}/
    override-targets?recordType=&q=&offset=&limit=` lists `{items: [{id, name, recordType, materialName}], total}`,
    alphabetical, `q` folded for case and accents after the names are decrypted. Every `Artifact` carries `overrides`
    (`{recordId, name, recordType, systemId, removed}` or null; `removed` when the record is tombstoned, superseded or
    out of scope). `ConversationDeletionArtifact` gains `overrides: boolean` (5.4 B's dialog row).
  - **Answers.** `scope` reads the marks through its own port `CampaignOverrides` (`knowledge` implements it; `scope`
    depends on no module that holds them, `ModuleBoundaryTest`). The records read (`JobRecord`, internal 1.27.0) gains
    optional `overrides` (on the artifact) and `overriddenBy` (on the record), each set only when both sides are in the
    job's frozen scope; the worker then reads a missing artifact first and tells the model it replaces the record.
  - **Reprocess (Q2).** At a reprocess's acceptance, where Story 4.5 carries corrections, a mark moves to the new
    record that matches the old one's (type, name fingerprint); with no match it stays, shown removed, and goes with the
    old record. A replacement upload carries nothing.
  - Log lines (ids and counts only): `knowledge.override_set`, `knowledge.override_removed`,
    `knowledge.override_refused`, `knowledge.override_targets_listed`, `artifacts.override_set`,
    `artifacts.override_removed`; `material.corrections_carried` gains `overrides`. Deploy order: backend, then worker,
    then frontend (the 1.49.0 frontend and the 1.26.0-reading worker behave as before).

- **Relate records (`knowledge`, `artifacts`, Story 5.5 part B, FR-20, public contract 1.51.0).** An artifact is
  related to other records with "related to"; the relation shows on both records and changes no answer.
  - **V51** on `record_relation`: the kind check gains `related`; part A's unique `(from_record_id, kind)` becomes the
    partial unique index `record_relation_overrides_from_key` on `from_record_id` where `kind = 'overrides'` (one
    override per artifact still), and `record_relation_from_to_kind_key` makes `(from_record_id, to_record_id, kind)`
    unique. Additive; the migration's header has the steps back to V50's keys.
  - **The rule.** `knowledge.service.RecordRelations`: a target is a live extracted record of any tier of the
    campaign's resolved scope (stored settings), or another live artifact of the same campaign (through the `scope`
    port `CampaignArtifacts`). A relation has no direction: one row, read from both sides; relating the other way, or
    again, stores nothing. At most 50 relations per artifact, either side (409 `artifact.relations_full`); anything
    that is no target (out of scope, a tombstone, another campaign's artifact, the artifact itself, another owner's,
    unknown, malformed) is 400 `artifact.relation_target`. Both rows are locked first. Not versioned.
  - **API.** `POST /api/v1/campaigns/{campaignId}/artifacts/{artifactId}/relations` `{recordId}` answers the artifact;
    `DELETE …/relations/{recordId}` is 204, idempotent, and removes the relation from either side;
    `GET …/artifacts/{artifactId}/relation-targets?recordType=&q=&offset=&limit=` lists
    `{items: [{id, name, recordType, tier, artifact, materialName}], total}`, alphabetical, `q` folded as part A's,
    leaving out the artifact and the records it is related to. Every `Artifact` carries `related`
    (`[{recordId, name, recordType, tier, artifact, removed}]`, oldest first; `removed` for an extracted record that is
    tombstoned, superseded or out of scope, whose `tier` is then null when out of scope; a deleting artifact is left
    out). `getCampaignRecord` alone gains `related: [{artifactId, name, recordType}]`, the campaign's live artifacts
    related to the record; the system's record read is unchanged.
  - **Reprocess.** A relation to an old version's record moves to the matching new record as an override does; one
    whose artifact is related to the match already is deleted.
  - Log lines (ids and counts only): `knowledge.relation_set`, `knowledge.relation_removed`,
    `knowledge.relation_refused`, `knowledge.relation_targets_listed`, `artifacts.relation_set`,
    `artifacts.relation_removed`; `material.corrections_carried` gains `related`; `knowledge.campaign_record_read`
    gains `related`. Deploy order: backend, then frontend (the 1.50.0 frontend behaves as before). Internal contract
    and worker unchanged.

- **Share an artifact (`artifacts`, `scope`, Story 5.6 part A, FR-38, public contract 1.52.0).** An artifact is shared
  with the owner's other campaigns of its system; it stays one artifact, so an edit shows everywhere.
  - **V52** `artifact_share` (artifact, target campaign, owner, created), one row per pair. The artifact's key cascades
    as `record_relation`'s; the campaign's has none, as every campaign key. No index job and no Qdrant point: sharing
    lives in Postgres only. Additive; the migration's header has the way back.
  - **The rule.** `artifacts.service.Artifacts#share` sets the whole set under the lock an edit takes on the newest
    version. A target is another readable campaign of the owner's of the artifact's system (archived or not); its own
    campaign, another system's, another owner's, a deleting one, unknown, malformed or null is 400
    `artifact.share_target` and nothing is stored.
  - **Scope.** The `scope` port `CampaignArtifacts` gains `shared` and `readShared` (kept apart from `ids`, `live` and
    `read`, which overrides and relations use). When campaign B's system tier is on, `Scopes.resolve` puts the live
    artifacts shared into B (their own campaign readable) into `ResolvedScope.shared`: the question input lists them
    after the system tier's sources, the records read answers them with tier `system`, citations store that tier,
    `status`/`hasSources(SYSTEM)` count them. With the tier off they are out, whatever the campaign tier. Their override
    and relations apply in their own campaign only. `removedArtifacts` counts an artifact shared into the campaign as
    live, whatever the tiers, until it is unshared or deleting, so B's stored citations then read as removed.
  - **API.** `PUT /api/v1/campaigns/{campaignId}/artifacts/{artifactId}/sharing` `{campaignIds}` (the whole set, at
    most 200; empty unshares all) answers the `Artifact`; from any campaign but the artifact's own it is 404. Every
    `Artifact` gains `sharedWith: [{campaignId, name, archived}]` (by name, readable campaigns only; empty in a read
    elsewhere) and `sharedFrom: {campaignId, name}` or null. `getArtifact` and `getArtifactVersion` in B also answer a
    live artifact shared into B, whatever B's tiers, with `sharedFrom` set, no `overrides`, no `related`, and a null
    `conversationId`; every other artifact route stays own-campaign only.
  - **Deletion.** The artifacts' deletion part removes the shares of the artifacts going and, for a campaign's or a
    system's deletion, the shares into the campaigns going, before `library` removes the campaign rows. From the mark
    on, an artifact that is deleting, or whose own campaign is, is no source and no read in B.
  - Log lines (ids and counts only): `artifacts.shared`, `artifacts.share_refused`; `artifacts.read` gains `shared`,
    `artifacts.deletion_removed` gains `shares`, `scope.resolved` and the question input's line gain `shared`. Deploy
    order: backend, then frontend (the 1.51.0 frontend behaves as before). Internal contract and worker unchanged.

- **Shared artifacts in the other campaigns (`artifacts`, `conversations`, Story 5.6 part B, public contract
  1.53.0).** What part A shared into campaign B is listed there and named where it is deleted.
  - **List.** `listArtifacts` in B holds B's own artifacts and the live artifacts shared into B (their own campaign
    readable, as part A's rule), whatever B's tiers: one list, newest first by id, with the same cursor and offset
    paging, and `total` and `types` count both. The record store's `artifacts`, `artifactCount` and `artifactTypes`
    take the shared ids (`campaign_id = :campaign or id in :shared`, ordered in SQL). Each `ArtifactSummary` gains
    `sharedFrom: {campaignId, name}` (its own campaign) or null for B's own; the row opens part A's read-only read.
  - **Dialog.** `ConversationDeletionArtifact` gains `shared`: true while the artifact is shared with at least one
    readable campaign, as `sharedWith` counts them (one batch of shares, `ArtifactShareStore#ofAll`, and one of
    campaigns).
  - No migration, no index change. Log lines: `artifacts.listed` gains `sharedShown`. Deploy order: backend, then
    frontend (the 1.52.0 frontend shows shared rows as plain rows that open the read-only page). Internal contract and
    worker unchanged.

- **Export artifacts (`artifacts`, Story 5.7 part A, FR-39, public contract 1.54.0).** A GM takes artifacts out of
  GMshield to print them or hand them to players.
  - **API.** `GET /api/v1/campaigns/{campaignId}/artifacts/export?ids=a,b&format=pdf|txt|markdown|jpg&language=pt-BR|en`
    (`ArtifactController.exportArtifacts`, written to the `HttpServletResponse` with no `produces`, as
    `MaterialController.picture` is, so a refusal stays problem JSON). 1 to 50 distinct ids, in the order given; each is
    read exactly as `Artifacts.get(campaignId, id)` reads it (the campaign's own and the live ones shared into it, at
    their newest version). Any unreadable id is 404 `request.not_found` naming none, and nothing is rendered; no ids,
    51, a duplicate (case ignored) or an empty one, an unknown format or language is 400 `request.invalid`; a malformed
    id is an unknown one. The answer is `Content-Disposition: attachment` with an ASCII `filename` and the UTF-8
    `filename*` (RFC 6266), `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
  - **Content** (`artifacts.service.ArtifactExports`): the name, the record type's name (a core type's in the export's
    language, as the frontend writes it, `CoreRecordTypes.shownName`; an approved type's as the system names it), the
    fields in the order written, the content, then "Fontes" / "Sources" as numbered lines: a material's place (material,
    section path, page), a record's type and name, an artifact's type, name and version, each removed one marked
    "(fonte removida)" / "(source removed)". Nothing is quoted beyond the artifact's own text (Dalton, 2026-11-27).
    Text is data: it is printed as text, never as Markdown (the Markdown export escapes every character that could make
    it markup, `ExportText.escaped`).
  - **Files.** PDF, TXT and Markdown are one file, each artifact a new section (a new A4 page in the PDF); JPG is one
    image for one artifact, and a zip of `01-<name>.jpg`, `02-…` for several. The file is named after the artifact (made
    safe: no `\ / : * ? " < > |`, control or format character, at most 200 UTF-8 bytes cut after a whole character, no reserved device name) or
    `artefatos` / `artifacts` for several.
  - **Rendering decision (recorded at the story's start).** Apache PDFBox 3.0.8 in the `artifacts` module
    (`ExportPdf`), with Noto Sans Regular and Bold bundled as resources and embedded as subsets, so accents print the
    same everywhere; a character neither font has prints as `?`. Lines wrap by the font's widths. JPG (`ExportJpg`)
    renders the artifact's own PDF pages at 110 DPI with `PDFRenderer`, stacks them into one image and writes it with
    `ImageIO`; an image stays within 65,000 pixels tall and 24 million pixels in all, so a long artifact is drawn at a
    lower resolution rather than exhausting the heap (every page is kept). Several JPGs are packed with
    `java.util.zip`. No outside service, no browser rendering, no worker change.
  - **Limits (Dalton, 2026-11-27).** A PDF or JPG export holds at most 200 PDF pages in all (`ExportPdf.Pages`, across
    every artifact's PDF): the 201st page is refused before it is drawn, so the rest is never built, with 400
    `artifact.export_too_large`. At most 2 PDF or JPG exports render at once (`ExportGate`); a third is refused at once,
    never queued, with 503 `artifact.export_busy`. TXT and Markdown are neither counted nor gated. With the 200-page cap,
    a JPG keeps every page, each a whole number of pixels tall, under 65,000 pixels in all (the JDK's JPEG writer takes
    at most 65,500).
  - **Memory only.** The PDF is built and read back with PDFBox's memory-only stream cache
    (`IOUtils.createMemoryOnlyStreamCache`), the JPEG is written through a `MemoryCacheImageOutputStream` (ImageIO's
    default for a stream is a temporary file), and the zip into a byte array: no temporary file is made
    (`ArtifactExportsTest`), so nothing is left once the answer is written. Checked in the production image (read-only
    root, `/tmp` tmpfs): headless AWT renders with the JRE as it is.
  - No migration, no internal-contract or worker change. Log lines (ids and counts only, never a name or text):
    `artifacts.exported` (`count`, `format`, `language`, `bytes`), `artifacts.export_not_found`,
    `artifacts.export_refused` (`reason`). Deploy order: backend,
    then frontend (the 1.53.0 frontend behaves as before).

- **Shields in a campaign (`library`, Story 6.1 parts A and B, public contracts 1.55.0 and 1.56.0, migration V53).** A Shield (Escudo do
  Mestre) is the GM's private space in a campaign, for notes (6.2) and dice (6.3, 6.4).
  - **V53** `shield` (id, owner, campaign, `name` and `name_fingerprint` both null or both set, `version`, created).
    Keys: (owner, id); (owner, campaign) to `campaign` with no cascade; unique (campaign, name fingerprint); and the
    partial unique index `shield_campaign_unnamed_key` (one unnamed Shield per campaign). It backfills one unnamed
    Shield (`uuidv7()`) per existing campaign. The migration's header has the way back (drop the table before running
    the previous release, whose campaign deletion would fail on the key). Warning: that drop deletes every Shield of
    every account for good, the typed names with them; back the table up first if they must come back.
  - **Every campaign has one.** `Library.createStarter` and `createCampaign` insert the campaign's first Shield, unnamed,
    in the campaign's transaction. A campaign the previous release created after V53 gets it when its Shields are
    listed (`Shields.shields`): the insert runs in its own transaction, and when another list won the race the partial
    index refuses it (`LibraryConflict.UNNAMED_SHIELD_TAKEN`) and the list reads again. The frontend shows an unnamed
    Shield as "Escudo do Mestre" / "GM's Shield".
  - **Rules** (`library.service.Shields`). A typed name is 1 to 120 code points after trimming
    (`request.shield_name_invalid`, 400), encrypted only through the vault (`Binding("shield", "name", id, owner)`) and
    unique within the campaign by the keyed fingerprint of its `uniqueForm` (`request.shield_name_taken`, 409). A
    rename names its version (`request.stale_version`, 409). Another owner's campaign or Shield, an unknown or malformed
    id, a Shield of another campaign and a campaign being deleted are all `request.not_found`.
  - **API** (`ShieldController`, all `no-store`): `GET /api/v1/campaigns/{id}/shields` (oldest first, `limit`, `cursor`
    or `offset`, `total`), `POST` (201), `GET` and `PATCH .../shields/{shieldId}` (`{version, name}`). Each `Shield` is
    `{id, name or null, version}`. Each route is a row of the cross-owner audit.
  - **Deletion.** A campaign's or a system's deletion removes the campaigns' Shields (`LibraryStore.deleteShieldsOf`) in
    the transaction that removes the campaign rows, before them.
  - **Deleting one Shield (part B, contract 1.56.0).** `GET .../shields/{shieldId}/deletion` answers
    `ShieldDeletion{notes, rolls, last}`: the counts are 0 until Stories 6.2 and 6.4 store notes and rolls, and `last`
    is true when the campaign has exactly one Shield. `DELETE .../shields/{shieldId}` deletes it at once (204, no
    recorded deletion process: a Shield holds nothing yet) and refuses the campaign's only Shield with
    `request.last_shield` (409), changing nothing (from Story 6.2 part B, the recorded process below, 202).
    `Shields.deleteShield` locks the campaign's row first
    (`LibraryStore.lockCampaign`, JPQL `PESSIMISTIC_WRITE`; the in-memory store holds a lock until the transaction
    ends), then counts, so two deletions at once leave one Shield (one 204, one 409). Not found is as above on both
    routes, an already deleted Shield included; both are rows of the cross-owner audit.
  - Log lines (ids and versions only): `library.shield_created`, `library.shield_renamed`, `library.shield_deleted`,
    `library.first_shield_created`, `library.first_shield_raced`. Deploy order: backend, then frontend (the 1.54.0
    frontend shows no Shields; the 1.55.0 frontend cannot delete one). Internal contract and worker unchanged.

- **Notes in a Shield (`artifacts` and `knowledge`, Story 6.2 part A, public contract 1.57.0, migration V54).** A note is
  an artifact of the campaign of record type `note` that also names the Shield holding it.
  - **V54** `record.shield_id` (nullable, owned by `knowledge`), with the key (owner, shield) to `shield` (no cascade:
    a campaign's or a system's deletion removes the campaign's artifacts before its Shields), the check
    `record_shield_check` (a held row is an artifact of type `note`) and the partial index `record_owner_shield`
    (owner, shield, id). `note` artifacts written before V54 keep a null Shield. The migration's header has the way
    back.
  - **Rules** (`artifacts.service.Artifacts#createNote`, `#notes`). A note is written through the same path as
    `Artifacts.create` (type `note`, no fields, version 1 and its index job, in one transaction), once
    `library.service.Shields#shield` finds the Shield in that campaign. Another owner's campaign or Shield, an unknown
    or malformed id, a Shield of another campaign and a campaign being deleted are all `request.not_found`. A held note
    stays a note: an edit or a restore to another type is `request.note_type_fixed` (409), and nothing is stored. It
    is the campaign's artifact everywhere else (the artifacts list and tab, sharing, relations, export) and a source
    of the campaign tier through `CampaignArtifacts`, with no code of its own.
  - **API** (`ShieldNoteController`, both `no-store`, both rows of the cross-owner audit):
    `POST /api/v1/campaigns/{id}/shields/{shieldId}/notes` `{name, content}` (201, `Artifact`) and `GET` on the same
    path (the Shield's live notes newest first, `limit`, `cursor` or `offset`, `NotePage{items, nextCursor, total}`).
    `Artifact` and `ArtifactSummary` carry `shieldId` (null for no Shield, and in a read from a campaign the note is
    shared into).
  - Log lines (ids and counts only): `artifacts.note_created`, `artifacts.notes_listed`, `artifacts.retype_refused`.
    Deploy order: backend, then frontend. Internal contract and worker unchanged.

- **Move a note, and delete a Shield with its notes (Story 6.2 part B, public contract 1.58.0, migration V55).**
  - **V55** `shield.deleting_at` (null for a live Shield), and `deletion_kind_check` allows `shield` too. The
    migration's header has the way back (let every pending `shield` deletion finish first).
  - **Move** (`Artifacts#move`, `PUT /api/v1/campaigns/{campaignId}/artifacts/{artifactId}/shield` `{shieldId}`, 200
    `Artifact` with the new `shieldId`, `no-store`, a row of the cross-owner audit). It creates no version, no index job
    and no change to the content (`ArtifactRecords#moveNote` writes `record.shield_id` on the locked row; V54's check
    still holds); moving a note to its own Shield answers it unchanged. A note no Shield holds, a Shield of another
    campaign, a deleting or unknown Shield and another owner's ids are `request.not_found` (404); a malformed body is
    `request.invalid` (400). No move between campaigns.
  - **The campaign lock.** `Artifacts#createNote` and `#move` find the Shield through
    `library.service.Shields#lockedShield`, which locks the campaign's row (`LibraryStore.lockCampaign`) and answers the
    live Shield under that lock. A Shield's deletion takes the same lock, so a note never lands in a Shield being
    deleted, and no `record_shield_fkey` failure reaches the client.
  - **Deleting a Shield** is now the recorded, resumable process (`SystemDeletion#deleteShield`, deletion kind
    `shield`). `Shields#deleteShield`, under the campaign lock: the live Shield (404 otherwise), not the last live one
    (`request.last_shield`, 409), then `LibraryStore.markShieldDeleting` (sets `deleting_at`; a named Shield's
    fingerprint becomes its id, so its name is free at once) and the deletion record, whose prepare
    (`ArtifactsDeletion`, `Going.ofShield`) marks the Shield's live notes deleting (with the locks a GM's delete of one
    artifact takes) and cancels their index jobs, in the same transaction. `DELETE .../shields/{shieldId}` answers 202
    with no body (was 204). From the commit the Shield is out of every Shield read and count, and its notes out of the
    lists, search and the campaign tier. The pass removes every note the Shield holds, live or deleting, as one
    artifact's deletion does (one `index.artifact_removal` job each), then `library` deletes the Shield's row and ends
    the record (`library.shield_deleted`, `removed`). A failed pass is retried by the sweep; a campaign's or a system's
    deletion that overtakes it removes everything, and the Shield's pass then ends without work.
  - **Preview.** `ShieldDeletion.notes` is the Shield's live notes, counted by the handlers
    (`SystemDeletion#previewShield`); `rolls` stayed 0 until Story 6.4 (below). `library` still depends only on `shared` and
    `vault`.
  - Log lines (ids and counts only): `artifacts.note_moved` (`found`, `moved`), `library.shield_delete` (`recorded`),
    `library.shield_deleting`, `library.shield_deleted` (`removed`), `artifacts.deletion_prepared` (`marked`). Deploy
    order: backend, then frontend (the 1.57.0 frontend reads the 202 as a success too). Internal contract and worker
    unchanged.

- **Keep the rolls (`library`, Story 6.4 part A, AD-31, public contract 1.59.0, migration V56).** The browser draws the
  dice (Story 6.3) and sends every roll; the backend checks it, keeps it once by the browser's key and lists it on
  every device. It never rolls and never edits a roll.
  - **V56** `roll` (id, owner, Shield, `roll_key`, `content`, `rolled_at`, created). Keys: (owner, id); unique (owner,
    `roll_key`); (owner, Shield) to `shield` with no cascade (as V54's note key). The index (owner, Shield, `rolled_at`
    desc, id desc) serves the log. The migration's header has the way back (drop the table; it deletes every stored
    roll for good).
  - **Rules** (`library.service.Rolls`, `DiceFormula`). A roll is stored only when its formula reads under the
    frontend's `src/dice.ts` grammar (`DiceFormula`, a Java copy with JavaScript's `\s` as white space), its results are
    its dice in order (one per die, each from 1 to its sides: `2d6-1d4` takes three), its label is at most 80 code
    points after trimming (blank for none) and `rolledAt` is at most 5 minutes ahead of the server clock; otherwise
    `request.roll_invalid` (400, `params.labelMax`). The total is computed here. The formula, the results and the label
    are one vault ciphertext (`Binding("roll", "content", id, owner)`); `rolled_at` stays plain.
  - **Exactly once.** A key the owner holds in that Shield answers 200 with the stored roll; in another of the owner's
    Shields, `request.roll_key_taken` (409). The insert is the library's one native statement
    (`insert ... on conflict (owner_id, roll_key) do nothing`, as `JdbcDeletionStore.insertIfAbsent`), then the row is
    read back, so a concurrent repeat is never a 500. Writes find the Shield through `Shields#lockedShield` (the
    campaign's row lock that a Shield's deletion takes).
  - **API** (`RollController`, all `no-store`, every route a row of the cross-owner audit):
    `POST /api/v1/campaigns/{id}/shields/{shieldId}/rolls` `{key, formula, results, label?, rolledAt}` (201 or 200,
    `Roll{id, key, formula, results, total, label, rolledAt}`), `GET` on the same path (newest first by `rolledAt`, then
    id; `limit`, `cursor`; `RollPage{items, nextCursor, total}`; a cleared cursor gives an empty page) and `DELETE`
    (clears the Shield's rolls, 204). A malformed body (not an object, a key missing or not a UUID) is
    `request.invalid`. Another owner's, a deleting Shield and a campaign being deleted are `request.not_found`.
  - **Deletion.** `ShieldDeletion.rolls` counts the Shield's stored rolls. `LibraryStore.deleteDeletingShield` and
    `deleteShieldsOf` remove the rolls before the Shield rows, so a Shield's, a campaign's and a system's deletion
    remove them. A roll never goes to the worker, the index, search, answers or a scope, and is not in a data export
    yet (Epic 8).
  - Log lines (ids and counts only): `library.roll_kept` (`created`), `library.rolls_cleared` (`count`). Deploy order:
    backend, then frontend (the 1.58.0 frontend stores no roll). Internal contract and worker unchanged.
  - **Part B (public contract 1.60.0).** `GET /api/v1/me` adds the account's `id` (required), which the browser
    keys its pending rolls with (`gmshield:rolls:{accountId}:{shieldId}` in its storage, AD-31). Nothing else changes
    here: the roll routes, the migrations, the internal contract and the worker stay as they are. Deploy order:
    backend, then frontend (the 1.59.0 frontend ignores the extra field).

- **The model setting (`limits`, Story 2.10, AD-18).** The one owner of the provider setting. `ModelSettings` reads
  the active provider (`GMSHIELD_MODEL_PROVIDER`: `azure-openai`, the default, `anthropic` or `openai`) and each
  provider's model id per tier (`GMSHIELD_MODEL_<AZURE_OPENAI|ANTHROPIC|OPENAI>_<FULL|LIGHTWEIGHT>`; Azure's are
  deployment names). An unknown provider, or a missing or invalid id (`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`) of the
  active one, stops the start with `setting.invalid` naming only the variables; the other providers' ids are not
  read. `ModelProviders` (`jobs.service.ModelChoices`) resolves `(provider, model, tier)` for an attempt: every job
  uses the `full` tier until Epic 9. There is no fallback provider, no price and no usage here (Story 9.2). The
  keys are the worker's: the backend holds none.
- **Ask a question (`conversations`, Story 2.4, public contract 1.11.0, internal contract 1.10.0).** The tenth module;
  it may use `shared`, `jobs`, `scope`, `library` and `vault`. `/api/v1/conversations/**` and `/api/v1/turns/**` join
  the library routes (`LibraryOwnerFilter`): the session's account is the owner.
  - `GET /api/v1/conversations/current` answers `{campaignId, hasSources, indexing}`: the current campaign, whether
    `Scopes.resolve(campaign, STARTING)` has an allowed source or a pending passage (from public contract 1.15.0, read
    through `Scopes.status`: an allowed source), and how many of that scope's passages are still waiting for search. No
    campaign is 404 `request.not_found`.
  - `POST /api/v1/campaigns/{campaignId}/turns` with `{question}` (1 to 4,000 code points, not blank; else 400
    `request.invalid`) answers 201 `{id, conversationId, language}`. In one transaction: the campaign's one
    conversation (made on the first question), the turn with the question encrypted (binding `turn`/`question`),
    `ScopeSettings.STARTING` frozen on it and its language, and its job of kind `question.answer` on the interactive
    lane. Another owner's campaign, an unknown one or a malformed id is 404, with nothing recorded. The language is
    `pt-BR`, `en` or null when unsure (`Languages`: a count of each language's short words, no library, no model).
    Only the turns asked since the page opened are shown (Dalton, 2026-11-18): there is no turns list until Story 3.10
    (below: a campaign has many conversations by area, each with its turns).
  - `GET /api/v1/turns/{turnId}/events` is `text/event-stream` (`TurnStreams`): the stored events after
    `Last-Event-ID` (0 when absent or not a number), then new ones, each `id:` its sequence (1, 2, … per turn) and
    named `delta {text}`, `citation {passageId, offset}`, `done {silent, references}` (Story 2.5, below) or
    `error {code}` (the public document names `TurnDeltaEvent`, `TurnCitationEvent`, `TurnDoneEvent` and
    `TurnErrorEvent`). A comment `: keep-alive` goes out
    after 15 s without an event (`gmshield.conversations.keep-alive`); the stream closes after `done` or `error`, or
    after 30 min (`gmshield.conversations.stream-timeout`). The database is the stream's truth: each stream runs on a
    virtual thread as the turn's owner (captured before it starts), is woken after each commit that stores an event,
    and polls every second (`gmshield.conversations.poll`) as a backstop, so replay, a reopened stream and a second
    instance need nothing more. A client that leaves never touches the job. Another owner's turn is 404.
  - `AnswerJobs` (`question.answer`, interactive, `callsModel(job)`): its input is `Scopes.questionInput` of the turn's
    campaign under its frozen settings, its passages `Scopes.passages` within that scope.
  - `POST /internal/v1/jobs/{jobId}/claims/{claimId}/events` (`jobs`), with `X-Job-Grant`: `{batch, events: [{type:
    delta, text} | {type: citation, passageId, offset}], usage: [{callId, responseId?, input, cacheRead, cacheWrite,
    output, reasoning, estimated?}]}` answers `{stop}`: true once the GM stopped the answer (Story 2.6, below). A
    malformed body is 400 `request.invalid`
    first; then the grant (403 `job.grant_refused`); then the batch (400 `job.events_invalid`: a number below 1, more
    than 500 events, a delta of 0 or more than 16,384 code points, a citation without its id or with a negative
    offset, more than 16 usages, a usage without its call id or with a negative count). On the job's locked row, as the
    owner, `JobHandler.events(job, batch)` stores it (default 403 `job.events_refused`, so `jobs` depends on no feature
    module): `batch` starts at 1 per claim; a repeated `(claim, batch)` is accepted and stored once; a gap is 409
    `job.batch_out_of_order` with nothing stored. Each batch is one encrypted JSON array (binding
    `turn_event_batch`/`payload`) whose events take the turn's next sequences. Its usage goes, in the same
    transaction, to `jobs.service.UsageReports`, which `limits` implements (`ModelUsage`): one `model_call_usage` row
    per call id (`ON CONFLICT DO NOTHING`) with the job, owner, the attempt's provider, model and tier, the response id
    and the token counts, and whether they are the worker's estimate (`estimated`, Story 2.6); no price (Story 9.2).
  - The end: `completed` stores the turn's source references (below), then appends `done`; the job's end in
    `failed` appends `error {code}` and stores no reference (a failed attempt that is retried appends nothing). **No splice:** once a turn has a posted batch holding an event (`JobHandler.delivered`; usage alone shows the GM nothing), a failed outcome
    ends the job as with `retry: false`, and a lapsed claim ends it `failed` with `job.claim_lapsed`: it is never offered
    again.
  - **Sources under every answer (Story 2.5, public contract 1.12.0; the internal contract stays 1.10.0).** In
    `AnswerJobs.completed`, inside the transaction that appends `done`, the passage ids of the turn's stored citations
    (in sequence order) are read again within the turn's scope, rebuilt from its frozen settings:
    `Scopes.references(scope, ids)` (through `ScopeReads.places`) keeps only the passages of the owner whose version
    is an allowed source still `ready`, and decrypts only the section path and the material's name, never a passage's
    text. An unknown, foreign or out-of-scope id is dropped, never refused; the line `conversation.references_stored`
    carries the cited, dropped and stored counts only (`cited` and `dropped` count distinct cited passage ids, not
    citation events). One reference per distinct place `(versionId, sectionPath,
    page)`, numbered 1…n by first citation, each with its `passageIds`. They are stored by value in `turn_reference`
    (`V18__turn_references.sql`, a library table): turn, number, version id, material id, page, passage ids, and the
    material's name and the section path encrypted (bindings `turn_reference`/`material_name` and
    `turn_reference`/`section_path`). No reference row points at `material_version` or `material`.
  - The `done` event's data is built when it is streamed, from `turn_reference`: `{silent, references: [{number,
    materialName, sectionPath: […], page, passageIds: […], removed}]}` (`TurnDoneEvent`, `TurnSourceReference`).
    `silent` is true exactly when `references` is empty: the frontend then shows the one "the sources say nothing"
    sentence. `removed` is true when the reference's version is gone, `discarded` or pending removal, or its material
    is gone (`Scopes.removedVersions`), as of the moment it is sent, so a replay from `Last-Event-ID` shows the current
    state. Delta, citation and error data do not change. While a turn's model knowledge is off (always, until Epic 3),
    every completed turn therefore has a reference or is silent. Rolling back below this release: roll the frontend
    back first (the reverse of the deploy order; the 2.5 frontend requires public contract 1.12.0 and reports
    unhealthy against an older backend), then the backend; `turn_reference` is then left unread.
  - **Stop an answer (Story 2.6, public contract 1.13.0, internal contract 1.11.0).** `POST /api/v1/turns/{turnId}/stop`
    answers 204 for any turn of the owner, whatever its state (so repeating it is safe), and 404 `request.not_found` for
    anything else. `Conversations.stop` runs in the owner's transaction and first locks the turn's job row (`Jobs.lock`:
    the lock the batch and outcome paths take first, so a stop and a batch are strictly ordered). A `waiting` or
    `offered` job is cancelled (`Jobs.cancel`); a `running` one keeps its claim, so the worker can still post usage and
    report. Unless the turn already has a final event, `AnswerJobs.stop` then stores the references of the citations so
    far (as `completed` does), appends `done` marked stopped (stored as `{"type": "done", "stopped": true}`) and sets
    `turn.stopped_at`: the GM's stream ends at once, before the worker hears of it. From then on: a batch stores no
    event (its usage is still recorded; line `conversation.batch_after_stop`), the reply to each batch is `{stop: true}`
    (`JobHandler.stopRequested`, read in the batch's locked transaction), `completed` and `failed` add no event and no
    reference (line `conversation.turn_already_ended`), and `delivered` is true, so a stopped job is never offered
    again, whether its claim lapses (`job.claim_lapsed`) or a failure is reported. A disconnect never stops a job: only
    this route does. The `done` event's data gains `stopped` (always present), and `silent` is true exactly when the
    answer was not stopped and has no reference: a stopped answer is never the silent one. A usage may say `estimated`
    (default false), stored in `model_call_usage.estimated`: the worker estimates a call it stopped before the provider
    sent its usage. `V19__answer_stop.sql` adds `turn.stopped_at timestamptz null` and `model_call_usage.estimated
    boolean not null default false`; both are additive, so the previous release keeps working on this schema. **Deploy
    order: backend, then worker (its minimum internal contract becomes 1.11.0), then frontend (its minimum public
    contract becomes 1.13.0).** Roll back in the reverse order. The `job.failed` line of a lapsed claim now logs its
    real code (`job.claim_lapsed` or `job.attempts_exhausted`).
  - **Open the cited page (Story 2.7, public contract 1.14.0; the internal contract and the worker do not change).**
    `GET /api/v1/turns/{turnId}/references/{number}/pages/{page}` answers one page of the version a stored source
    reference names, with the same shape as `MaterialPageView` (the OpenAPI document points at that schema), and `GET
    …/pages/{page}/picture` streams its WebP picture (`image/webp`, `Content-Length`, `nosniff`); both `no-store`. The
    path is the ground (AD-9): "a source reference stored with a turn of that campaign". `Conversations` checks that the
    owner's turn exists, that its conversation's campaign is the owner's and that the turn has a reference with that
    number; `Scopes.referencePage`/`referencePicture` then serve the page from the stored `versionId` and `materialId`
    through `ScopeReads.versionPage`/`versionPicture`, which reuse `Materials.versionPage` (the review ground's read).
    The cited version is served even when a newer one was accepted since (`superseded`), never once it is removed
    (`Scopes.removedVersions`' rule) or neither `ready` nor `superseded`; any page from 1 to its page total, one per
    request. Everything else is 404 `request.not_found`: another owner's turn, a malformed or unknown id or number, no
    such reference, a removed version, a page out of range, a page with no view (an unreadable page with no view answers
    `picture: null`, `outcome: unreadable`, as on the review ground) and, for the picture, a page with no picture. No
    page is ever served on a passage or material id the client sends. The materials route keeps `?ground=review` only
    (`ground=reference` stays 400). Lines (`conversation.reference_page`, `conversation.reference_picture`,
    `scope.reference_page`) carry ids and numbers only. No migration. **Deploy order: backend, then frontend (its
    minimum public contract becomes 1.14.0).** Roll back in the reverse order.
  - **The indexing count (public contract 1.15.0; the internal contract and the worker do not change).**
    `CurrentConversation.indexing` (schema `IndexingCount`) is `{remaining}` (an integer, at least 1), or null when
    nothing in scope is pending. The frontend shows it as "Preparando as fontes para busca: faltam {remaining} trechos".
    Polling: while the tab is visible, every 5 s, the frontend reads `current` again; a failed read keeps the number;
    polling stops after 10 minutes of unchanged successful reads, and resumes on a new count from the page.
    `current` now asks `Scopes.status(campaign, STARTING)` instead of `resolve`: one look-up of the in-scope sources
    (the campaign and system tiers' `ready` versions, as `resolve` takes them), `hasSources` from those sources alone
    (a pending passage always belongs to one, so its meaning is unchanged), and `indexing` from `ScopeReads.indexing`;
    no pending passage id is loaded (`Scopes.indexing` answers the count alone). `remaining` is the number of distinct
    passages of those versions covered by an unended (`waiting`, `offered` or `running`) `index.change`: one `count`
    query per run's ordinal range (`MaterialStore.countPassages(version, first, last)`, overlapping runs merged first),
    so no passage id or text is loaded. It only falls as runs end, unless something new is accepted. An ended (`done`,
    `failed`, `cancelled`) change, a version of another campaign or of a switched-off tier, and another owner's are not
    counted. The line `scope.indexing` carries ids and counts only, at DEBUG (it comes every 5 s per open tab). No migration.
    - Indexing starts at the worker's start since internal contract 1.12.0, and failed changes are reported apart
      (`indexingFailed`; "Indexing at worker start" above).
    - **Deploy order: backend, then frontend (its minimum public contract becomes 1.15.0).** Roll back in the reverse
      order.
  - `V17__conversations.sql`: `conversation`, `turn`, `turn_event_batch` (library tables, every key with the owner;
    `(claim_id, batch)` unique; a final event has no claim or number) and `model_call_usage` (keyed by call id, written
    through `JdbcClient` in the caller's transaction). **A backend rolled back below this release must first cancel
    every unended `question.answer` job**, with the backend stopped, as `gmshield_backend`:

    ```sql
    update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null,
                   outcome_code = null, updated_at = now()
     where kind = 'question.answer' and state in ('waiting', 'offered', 'running');
    ```

  - Lines carry ids, counts and codes, never a question, an answer's text or a passage's text.
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
    `kind`, the handler's `input`, the job's `traceparent` and, for a kind that calls a model, its `model`
    (Story 2.10, below). The optional body states the worker's `indexRecipe` (Story 2.1) and `providers`. A live claim answers `409 job.claimed`; a job
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
  - **Models (Story 2.10, AD-18, internal contract 1.9.0).** A `JobHandler` whose `callsModel(job)` is true (default
    false; `question.answer` from Story 2.4, an ingestion records step from Story 4.2) gets a model per attempt. A claim may state `providers` (at most 8, each
    `^[a-z0-9-]{1,32}$`, else 400 `request.invalid` before the claim): those the worker holds a key for. For such a
    kind each claim asks `jobs.service.ModelChoices` (implemented by `limits`) for the provider, model and tier,
    records them on the job row (`model_provider`, `model_id`, `model_tier`, `V16__job_model.sql`; overwritten at
    every claim, so a retry resolves them again, and kept once the job ends, naming the model that answered) and
    answers them as `model: {provider, model, tier}`. When the worker did not state that provider (no `providers`
    means none), the claim rolls back without using an attempt and the job ends `failed` with
    `job.provider_unavailable`, as a job whose input failed does; the claim is answered `409 job.not_offered`. A kind
    that calls no model claims as before, with no `model` in the answer, whatever the providers. `job.claimed` logs
    `modelProvider` and `modelTier` (`none` for other kinds).
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
| `GMSHIELD_VERSION_REMOVAL_SWEEP_EVERY` | how often the removals of discarded versions that stopped half way are finished (default `10m`) |
| `GMSHIELD_INDEX_RETRY_EVERY` | how often the index changes whose job failed get a new job, each run at most 3 times (default `10m`) |
| `GMSHIELD_SYSTEM_DELETION_SWEEP_EVERY` | how often the deletions of systems and campaigns that are due are run again, the first right after the start (default `10m`) |
| `GMSHIELD_JOB_OUTPUT_MAX_BYTES` | the largest file a worker may send back for a job (default `3GB`, a Spring data size in binary units) |
| `GMSHIELD_JOB_OUTPUT_CLAIM_MAX_BYTES` | all the files one claim may send back together (default `8GB`; at least the output limit) |
| `GMSHIELD_INGESTION_STEPS_PER_PAGE` | a version may have at most this many steps per page, plus one (default `4`, 1 to 100) |
| `GMSHIELD_MODEL_PROVIDER` | the active model provider (Story 2.10): `azure-openai` (default), `anthropic` or `openai`; there is no fallback. Its two model ids below must be set and valid, else `setting.invalid` |
| `GMSHIELD_MODEL_AZURE_OPENAI_FULL`, `GMSHIELD_MODEL_AZURE_OPENAI_LIGHTWEIGHT` | Azure OpenAI's deployment per tier (default `gpt-6.1-terra` for both) |
| `GMSHIELD_MODEL_ANTHROPIC_FULL`, `GMSHIELD_MODEL_ANTHROPIC_LIGHTWEIGHT` | Anthropic's model per tier (default `claude-sonnet-5-5` and `claude-haiku-5-5`) |
| `GMSHIELD_MODEL_OPENAI_FULL`, `GMSHIELD_MODEL_OPENAI_LIGHTWEIGHT` | OpenAI's model per tier (no default: OpenAI cannot be selected until both are set) |
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
