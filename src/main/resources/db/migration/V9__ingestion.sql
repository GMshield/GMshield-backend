-- Story 1.15 (AD-11, AD-17, AD-19, AD-21): the chain of ingestion steps of a material version, and the files the
-- steps store for it. The previous backend release writes none of it and keeps working.
-- material_version records what the chain reports: its page total once known, the worker's pipeline version from the
-- first report, and the failure code of a version that ended failed (a code, never a message).
-- ingestion_step and version_file are library tables: each row carries its owner, filled and filtered by Hibernate
-- @TenantId, and every key and reference includes it, as in V4 and V7. A step is one ingestion job (job.id, kind
-- 'ingestion') over one stage, which the worker names and the backend only records, of a page range (none: the
-- whole file) of an input file (the raw upload, or one of the version's files). A version file is a file of the
-- encrypted file store, under the owner and its file id: the cleaned original whole, or a batch of its pages, with
-- its size and piece count. Neither holds content, a name or a hash. Structure only: no trigger, procedure or
-- row-level security.
alter table material_version
    add column page_total       integer     check (page_total between 1 and 1000000),
    add column pipeline_version varchar(32),
    add column failure_code     varchar(64);

create table ingestion_step (
    id            uuid        primary key,
    owner_id      uuid        not null,
    version_id    uuid        not null,
    job_id        uuid        not null,
    stage         varchar(32) not null,
    first_page    integer     check (first_page >= 1),
    last_page     integer,
    input_file_id uuid        not null,
    created_at    timestamptz not null,
    constraint ingestion_step_owner_id_key unique (owner_id, id),
    constraint ingestion_step_owner_job_key unique (owner_id, job_id),
    constraint ingestion_step_pages_check check (
        (first_page is null and last_page is null) or (first_page is not null and last_page >= first_page)),
    constraint ingestion_step_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create index ingestion_step_owner_version on ingestion_step (owner_id, version_id);

create table version_file (
    id          uuid        primary key,
    owner_id    uuid        not null,
    version_id  uuid        not null,
    file_id     uuid        not null,
    role        varchar(16) not null check (role in ('original', 'batch')),
    first_page  integer     not null check (first_page >= 1),
    last_page   integer     not null,
    size_bytes  bigint      not null check (size_bytes >= 1),
    piece_count integer     not null check (piece_count >= 1),
    created_at  timestamptz not null,
    constraint version_file_owner_id_key unique (owner_id, id),
    constraint version_file_owner_file_key unique (owner_id, file_id),
    constraint version_file_pages_check check (last_page >= first_page),
    constraint version_file_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create index version_file_owner_version on version_file (owner_id, version_id, role, first_page);
