-- Story 1.16 (AD-17, AD-19): what the ingestion chain learned about each page of a version, as ranges of pages
-- (inclusive), so a report and its transaction stay small whatever the page count (a 2 GB text upload can make about
-- 700,000 pages). The previous backend release writes none of it and keeps working.
-- page_layer says whether a page has a text layer ('text') or is an image to read by OCR ('image'); page_outcome
-- says how a page was read: 'read_directly', 'read_by_ocr' or 'unreadable'. A page has at most one layer and one
-- outcome: the backend checks that no range overlaps another of the same version, in JPQL, inside the report's
-- transaction (no exclusion constraint). Both are library tables: each row carries its owner, filled and filtered by
-- Hibernate @TenantId, and every key and reference includes it, as in V4, V7 and V9. Neither holds content.
-- ingestion_step.ended_at is set when the step's job is completed or finally fails (or is cancelled by a restart):
-- the chain has ended when no step of the version is left without it.
-- version_file gains the role 'text': the pages' extracted text, one file per step, with the pages it holds.
-- Structure only: no trigger, procedure or row-level security.
create table page_layer (
    id          uuid        primary key,
    owner_id    uuid        not null,
    version_id  uuid        not null,
    first_page  integer     not null check (first_page >= 1),
    last_page   integer     not null,
    layer       varchar(16) not null check (layer in ('text', 'image')),
    created_at  timestamptz not null,
    constraint page_layer_owner_id_key unique (owner_id, id),
    constraint page_layer_pages_check check (last_page >= first_page),
    constraint page_layer_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create index page_layer_owner_version on page_layer (owner_id, version_id, first_page);

create table page_outcome (
    id          uuid        primary key,
    owner_id    uuid        not null,
    version_id  uuid        not null,
    first_page  integer     not null check (first_page >= 1),
    last_page   integer     not null,
    outcome     varchar(16) not null check (outcome in ('read_directly', 'read_by_ocr', 'unreadable')),
    created_at  timestamptz not null,
    constraint page_outcome_owner_id_key unique (owner_id, id),
    constraint page_outcome_pages_check check (last_page >= first_page),
    constraint page_outcome_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create index page_outcome_owner_version on page_outcome (owner_id, version_id, first_page);

alter table ingestion_step add column ended_at timestamptz;

create index ingestion_step_owner_version_unended on ingestion_step (owner_id, version_id) where ended_at is null;

alter table version_file drop constraint version_file_role_check;
alter table version_file add constraint version_file_role_check check (role in ('original', 'batch', 'text'));
