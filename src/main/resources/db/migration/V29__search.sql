-- Story 3.9 (AD-9, AD-11): a search of the library and its results. The previous backend release neither reads nor
-- writes either table, and it has no handler for the kind 'library.search': a backend rolled back below this release
-- must first cancel every unended job of that kind (README.md, "Search the library", gives the statement).
-- search is one search of a campaign's scope: the query is stored only encrypted through the vault, bound to the entity
-- "search", the field "query", the row and the owner, and is never matched in SQL. Its filters (tier, material) only
-- narrow the campaign's scope; the material is kept by value, with no reference to material, which may be removed.
-- state is pending until its job of kind 'library.search' ends: done with its results, or failed with the job's code.
-- Only the newest 20 searches of an owner are kept: older ones are pruned when a search is created.
-- search_result is one result of a done search, in rank order (1..20): the passage id, its version and the tier its
-- source had when the search ran, all by value; the passage is re-checked against the campaign's current scope each
-- time the search is read, so a result that left the scope is not shown. Ids and words only, no text.
-- Both are library tables: each row carries its owner, filled and filtered by Hibernate @TenantId, and every key and
-- reference includes it, as in V17. Structure only: no trigger, procedure or row-level security.
create table search (
    id          uuid        primary key,
    owner_id    uuid        not null,
    campaign_id uuid        not null,
    job_id      uuid        not null,
    query       bytea       not null,
    tier        varchar(16),
    material_id uuid,
    state       varchar(16) not null,
    code        varchar(64),
    created_at  timestamptz not null,
    constraint search_owner_id_key unique (owner_id, id),
    constraint search_owner_job_key unique (owner_id, job_id),
    constraint search_tier_check check (tier in ('campaign', 'system', 'other')),
    constraint search_state_check check (state in ('pending', 'done', 'failed')),
    constraint search_code_check check ((state = 'failed') = (code is not null)),
    constraint search_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id)
);

create index search_owner_created on search (owner_id, created_at);

create table search_result (
    id         uuid        primary key,
    owner_id   uuid        not null,
    search_id  uuid        not null,
    rank       integer     not null check (rank between 1 and 20),
    passage_id uuid        not null,
    version_id uuid        not null,
    tier       varchar(16) not null,
    constraint search_result_owner_id_key unique (owner_id, id),
    constraint search_result_rank_key unique (owner_id, search_id, rank),
    constraint search_result_passage_key unique (owner_id, search_id, passage_id),
    constraint search_result_tier_check check (tier in ('campaign', 'system', 'other')),
    constraint search_result_search_fkey foreign key (owner_id, search_id) references search (owner_id, id)
);
