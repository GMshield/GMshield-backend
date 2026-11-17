-- Story 1.18 (AD-11, AD-17, AD-19): the passages of a material version and its outline of sections, and the held
-- final step that reads every text file of the version. The previous backend release writes none of it and keeps
-- working: the steps it writes still have a job and an input file.
-- A held step is an ingestion_step with no job yet (job_id null): its input is every text file of the version
-- (input_texts, with no input_file_id), which do not exist until every page has an outcome. The report that leaves
-- every page with an outcome and only held steps unended gives each its job, in that transaction.
-- passage and version_outline are library tables: each row carries its owner, filled and filtered by Hibernate
-- @TenantId, and every key and reference includes it, as in V4, V7, V9 and V10. A passage's text and its place (its
-- section path with its boxes on its page, or its range in its page's text, as JSON) are stored only encrypted
-- through the vault, each bound to the entity "passage", its field, the row and the owner; its ordinal (reading
-- order within the version) and its page are plain numbers. version_outline holds one encrypted JSON outline per
-- version, bound to the entity "version_outline", the field "outline", the row and the owner. A report carrying
-- passages replaces the version's passages and outline. Structure only: no trigger, procedure or row-level security.
alter table ingestion_step alter column job_id drop not null;
alter table ingestion_step alter column input_file_id drop not null;
alter table ingestion_step add column input_texts boolean not null default false;
alter table ingestion_step add constraint ingestion_step_input_check check (
    (input_texts and input_file_id is null) or (not input_texts and input_file_id is not null));
alter table ingestion_step add constraint ingestion_step_held_check check (job_id is not null or input_texts);

create table passage (
    id          uuid        primary key,
    owner_id    uuid        not null,
    version_id  uuid        not null,
    ordinal     integer     not null check (ordinal >= 0),
    page        integer     not null check (page between 1 and 1000000),
    text        bytea       not null,
    place       bytea       not null,
    created_at  timestamptz not null,
    constraint passage_owner_id_key unique (owner_id, id),
    constraint passage_owner_version_ordinal_key unique (owner_id, version_id, ordinal),
    constraint passage_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create table version_outline (
    id          uuid        primary key,
    owner_id    uuid        not null,
    version_id  uuid        not null,
    outline     bytea       not null,
    created_at  timestamptz not null,
    constraint version_outline_owner_id_key unique (owner_id, id),
    constraint version_outline_owner_version_key unique (owner_id, version_id),
    constraint version_outline_version_fkey foreign key (owner_id, version_id)
        references material_version (owner_id, id)
);
