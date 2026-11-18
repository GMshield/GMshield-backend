-- Story 1.12 (AD-15): background work that survives a restart. One row per job, written in the transaction that
-- causes it, and the only record of its state, order, attempts and outcome; Redis carries nothing but its id.
-- Not a library table: no @TenantId and no content, input or result. The owner is the account the work runs as
-- (bound with OwnerContext.callAs before a handler runs). The grant is never stored, only its vault token hash
-- (purpose "grant"). Times come from the application. Structure only: no trigger, procedure or row-level security.
create table job (
    id              uuid        primary key,
    owner_id        uuid        not null,
    kind            varchar(64) not null,
    lane            varchar(16) not null check (lane in ('interactive', 'ingestion')),
    state           varchar(16) not null
                    check (state in ('waiting', 'offered', 'running', 'done', 'failed', 'cancelled')),
    attempt         integer     not null default 0 check (attempt >= 0),
    trace_id        varchar(32) not null,
    offered_at      timestamptz,
    claim_id        uuid,
    grant_hash      bytea,
    claim_renew_at  timestamptz,
    claim_lapses_at timestamptz,
    outcome_code    varchar(64),
    created_at      timestamptz not null,
    updated_at      timestamptz not null
);

-- What to offer next (waiting jobs of a lane, oldest first) and what the sweep looks at (offered and running).
create index job_state_lane_created_at on job (state, lane, created_at);
-- The owner offered last in a lane: the ingestion lane's round robin.
create index job_lane_offered_at on job (lane, offered_at);
