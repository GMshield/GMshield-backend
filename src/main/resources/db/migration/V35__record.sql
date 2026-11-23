-- Story 4.2 (part A): the records a material version's ingestion extracts (rules, spells and items), each linked to the
-- passages it was read from, and the ingestion steps that extract them.
-- A record has its core type's key in `type` (rule, spell or item in this release; the check names every core key, as
-- record_type.core does), so each system its material belongs to shows it under its own core type, and moving the
-- material changes nothing. Its name and its fields (an ordered list of name/value pairs, as JSON) are stored only
-- encrypted through the vault, each bound to the entity "record", its field ("name" or "fields"), the row and the owner;
-- its ordinal (its place in the report of its step) and its first page (the lowest page of its passages) are plain
-- numbers. record_passage links a record to each passage it was read from (at least one, from its step's pages).
-- Both are library tables: each row carries its owner, filled and filtered by Hibernate @TenantId, and every key and
-- reference includes it, as in V4, V9 and V11. A record names its version, and a link its record and its passage, with
-- the owner, and no reference cascades: whatever replaces or removes a version's passages (a passages report, a restart,
-- a reprocess, a discard, a deletion) removes its records first, in the same transaction.
-- ingestion_step: a `records` step reads the passages of its pages (input_passages, with neither input_file_id nor
-- input_texts), and records_unread marks one that failed for good, so the quality report counts its pages as unread
-- (cleared whenever the version's records are). The previous backend release neither writes nor understands a records
-- step: rolling back past V35 is safe only while no records step exists, which holds until the first release with V35.
-- Structure only: no trigger, procedure or row-level security.
alter table ingestion_step add column input_passages boolean not null default false;
alter table ingestion_step add column records_unread boolean not null default false;
alter table ingestion_step drop constraint ingestion_step_input_check;
alter table ingestion_step add constraint ingestion_step_input_check check (
    (input_texts and not input_passages and input_file_id is null)
    or (input_passages and not input_texts and input_file_id is null and first_page is not null)
    or (not input_texts and not input_passages and input_file_id is not null));
alter table ingestion_step add constraint ingestion_step_unread_check check (input_passages or not records_unread);

create table record (
    id          uuid        primary key,
    owner_id    uuid        not null,
    version_id  uuid        not null,
    ordinal     integer     not null check (ordinal >= 0),
    first_page  integer     not null check (first_page between 1 and 1000000),
    type        varchar(32) not null,
    name        bytea       not null,
    fields      bytea       not null,
    created_at  timestamptz not null,
    constraint record_type_check check (type in ('rule', 'spell', 'item', 'creature', 'character_sheet', 'encounter',
                                                  'dungeon', 'note')),
    constraint record_owner_id_key unique (owner_id, id),
    constraint record_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create index record_owner_version on record (owner_id, version_id, first_page);

create table record_passage (
    owner_id    uuid not null,
    record_id   uuid not null,
    passage_id  uuid not null,
    constraint record_passage_pkey primary key (owner_id, record_id, passage_id),
    constraint record_passage_record_fkey foreign key (owner_id, record_id) references record (owner_id, id),
    constraint record_passage_passage_fkey foreign key (owner_id, passage_id) references passage (owner_id, id)
);

create index record_passage_owner_passage on record_passage (owner_id, passage_id);
