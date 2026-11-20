-- Story 2.4 (AD-11, AD-19, AD-25): a campaign's conversation, its turns, the answer events of each turn, and the usage
-- of each model call. The previous backend release neither reads nor writes any of them, and it has no handler for the
-- kind 'question.answer': a backend rolled back below this release must first cancel every unended job of that kind
-- (README.md, "Ask a question", gives the statement).
-- conversation is a campaign's one conversation (until Story 3.10 adds more). turn is one question: the question is
-- stored only encrypted through the vault, bound to the entity "turn", the field "question", the row and the owner; its
-- language (pt-BR, en or null when unsure), the scope settings frozen with it, and the job of kind 'question.answer'
-- that answers it. turn_event_batch is one numbered batch of answer events the worker posted under one claim, or the
-- turn's final event (done or error: no claim, no batch number). Its events are stored only encrypted, as one JSON
-- array bound to the entity "turn_event_batch", the field "payload", the row and the owner; they take the sequences
-- first_sequence to last_sequence of the turn (an empty batch, usage only, takes none: last = first - 1). A (claim,
-- batch) is stored once. These three are library tables: each row carries its owner, filled and filtered by Hibernate
-- @TenantId, and every key and reference includes it, as in V4 and V11.
-- model_call_usage is one model call's tokens, keyed by the worker's call id and written once (ON CONFLICT DO
-- NOTHING): the job, its owner, the provider, model and tier of the attempt, the provider's response id when there is
-- one, and the token counts. Ids, words and numbers only, no price (Story 9.2). Structure only: no trigger, procedure
-- or row-level security.
create table conversation (
    id          uuid        primary key,
    owner_id    uuid        not null,
    campaign_id uuid        not null,
    created_at  timestamptz not null,
    constraint conversation_owner_id_key unique (owner_id, id),
    constraint conversation_owner_campaign_key unique (owner_id, campaign_id),
    constraint conversation_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id)
);

create table turn (
    id              uuid        primary key,
    owner_id        uuid        not null,
    conversation_id uuid        not null,
    job_id          uuid        not null,
    question        bytea       not null,
    language        varchar(8),
    campaign_tier   boolean     not null,
    system_tier     boolean     not null,
    model_knowledge boolean     not null,
    created_at      timestamptz not null,
    constraint turn_owner_id_key unique (owner_id, id),
    constraint turn_owner_job_key unique (owner_id, job_id),
    constraint turn_language_check check (language in ('pt-BR', 'en')),
    constraint turn_conversation_fkey foreign key (owner_id, conversation_id) references conversation (owner_id, id)
);

create index turn_owner_conversation on turn (owner_id, conversation_id, created_at);

create table turn_event_batch (
    id             uuid        primary key,
    owner_id       uuid        not null,
    turn_id        uuid        not null,
    claim_id       uuid,
    batch          integer,
    first_sequence bigint      not null check (first_sequence >= 1),
    last_sequence  bigint      not null,
    payload        bytea       not null,
    created_at     timestamptz not null,
    constraint turn_event_batch_owner_id_key unique (owner_id, id),
    constraint turn_event_batch_claim_batch_key unique (claim_id, batch),
    constraint turn_event_batch_batch_check check ((claim_id is null) = (batch is null) and (batch is null or batch >= 1)),
    constraint turn_event_batch_sequences_check check (last_sequence >= first_sequence - 1),
    constraint turn_event_batch_turn_fkey foreign key (owner_id, turn_id) references turn (owner_id, id)
);

create index turn_event_batch_owner_turn on turn_event_batch (owner_id, turn_id, last_sequence);

create table model_call_usage (
    call_id      uuid        primary key,
    owner_id     uuid        not null,
    job_id       uuid        not null,
    provider     varchar(32)  not null,
    model        varchar(128) not null,
    tier         varchar(16)  not null,
    response_id  varchar(256),
    input_tokens bigint      not null check (input_tokens >= 0),
    cache_read   bigint      not null check (cache_read >= 0),
    cache_write  bigint      not null check (cache_write >= 0),
    output_tokens bigint     not null check (output_tokens >= 0),
    reasoning    bigint      not null check (reasoning >= 0),
    created_at   timestamptz not null
);

create index model_call_usage_owner_job on model_call_usage (owner_id, job_id);
