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
Ten modules exist so far: `shared` (the error model, trace ids, log lines, start-up checks, the
contract versions, the owner context and `Ids`, which mints every id as a UUID v7), `vault` (encryption at rest,
below), `audit` (the append-only audit log, below; it may use `shared`), `jobs` (background work that
survives a restart, below; it may use `shared` and `vault`, and no feature module), `library` (systems and
campaigns, below; it may use `shared` and `vault`), `materials` (materials and the resumable upload of their
files, below; it may use `shared`, `vault`, `jobs` and `library`), `scope` (what a campaign may use, below; it may
use `shared`, `library`, `materials` and `vault`), `limits` (the model setting and the usage of model calls, below; it may use `shared` and
`jobs`), `conversations` (a campaign's conversation and its answers, Story 2.4, below; it may use `shared`, `jobs`,
`scope`, `library` and `vault`) and `accounts` (sign-in with Google, accounts and sessions, below; it may use `shared`, `vault`, `audit` and
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
  - `GET /api/v1/campaigns/current` (the owner's current campaign, with its system: Story 3.2, below), `GET
    /api/v1/campaigns/{id}` and `GET /api/v1/systems/{id}`: another owner's id, an unknown id and a
    malformed id all answer the same `request.not_found` (404). Every answer is `no-store`.
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
    `nextCursor`; anything else is `request.invalid` (400). JPQL only (`JpaMaterialStore.newestIn`). From Story 3.3
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
  - `ScopeSettings(campaignTier, systemTier, otherTier, modelKnowledge)`, a parameter of every resolve and stored
    nowhere (Story 2.4 freezes it with a job, Story 3.5 stores it per campaign). `ScopeSettings.STARTING` is campaign
    and system on, other and model knowledge off; `otherTier = true` is refused (`IllegalArgumentException`) until
    Story 3.5.
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
    job that carries a question, from a `ResolvedScope` only, as its owner: `{question, owner, tiers: [{tier,
    sources, pending}], tokenKey}`. Both tiers are always listed, in order (campaign, then system), a tier without a
    source with empty lists; `pending` is that tier's pending passage ids, at most the first `Scopes.MAX_PENDING`
    (5,000 from indexing at worker start, 100 before; the worker reads them 100 at a time within a time budget) in
    order, so a re-index of a whole library never fills the claim reply (the total is logged as a count, `pending`,
    beside `pendingListed` and `pendingLeftOut`); `owner` is the scope's owner, for the worker's Qdrant
    filter; `tokenKey` is Base64 of `Vault.ownerTokenKey(owner)`, whose buffer is zeroed once encoded (so `scope` may
    use `vault :: service`). The question is 1 to 4,000 characters (code points), else
    `IllegalArgumentException`; a scope of another owner is `IllegalStateException`. Provider, model and tier come
    with the claim (Story 2.10, `model`), never in this input. The handler's `input(JobRef)` returns it, so the claim reply carries it unchanged in `input`
    (no claim schema change). Lines (`scope.question_input`) carry counts, never the question or the key. No
    production kind carries a question until Story 2.4; the test-only kind `test.question` (`ScopedPassagesFlowTest`)
    proves that the claim reply carries the input and that the passages route serves the ids the worker found. The
    worker's search (`GMshield-worker`, `retrieval/search.py`) needs contract 1.8.0, unchanged here.
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
    Only the turns asked since the page opened are shown (Dalton, 2026-11-18): there is no turns list until Story 3.10.
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
  - `AnswerJobs` (`question.answer`, interactive, `callsModel()`): its input is `Scopes.questionInput` of the turn's
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
  - **Models (Story 2.10, AD-18, internal contract 1.9.0).** A `JobHandler` whose `callsModel()` is true (default
    false; `question.answer` from Story 2.4) gets a model per attempt. A claim may state `providers` (at most 8, each
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
