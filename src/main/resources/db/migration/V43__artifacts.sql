-- Story 5.1 (part A): the GM writes an artifact by hand. An artifact is a record of its campaign: its identity and its
-- type key are a `record` row, as an extracted record's are (the `knowledge` module owns both), and its content (name,
-- fields and an optional free text) is a numbered version in the new table `record_version`, owned by the `artifacts`
-- module. Part A writes version 1 only; later stories add the next numbers and never change an earlier one.
-- record: an extracted record names its material version (version_id, with its ordinal, first page, name and fields, as
-- before); an artifact names its campaign (campaign_id) instead and has none of those, since its name and fields live
-- in its versions. Exactly one of version_id and campaign_id is set. Every read of an extracted record goes through a
-- material version or through ids that the extraction gave out, and leaves out the rows without one (the
-- `knowledge.data` stores), so an artifact is never listed, counted, opened, searched or indexed as a system's record.
-- The campaign reference includes the owner, as every library key does; a campaign's deletion removes its artifacts
-- (versions first) before the campaign row. The index on (owner, campaign, type) serves a campaign's artifacts tab's
-- type filter and its counts by type, and the one on (owner, campaign, id) its list of every type, newest first.
-- record_version: one version of an artifact's content, keyed by its record and its number (from 1). Its name, fields
-- (a JSON array of {name, value}, in order) and content are stored only encrypted through the vault, each bound to the
-- entity "record_version", its field ("name", "fields" or "content"), the record's id and the owner; content is null
-- when the GM wrote none. It names its record with the owner, so it can never point at another owner's record.
-- Rolling back past V43 is safe: this migration only adds a column, a table, checks and indexes, and relaxes NOT
-- NULLs. The previous backend release neither writes nor reads an artifact: each of its record reads goes through a
-- version id, or through an id that a version's record or a citation gave out, so it never reaches an artifact row. Its
-- own inserts always set the version, ordinal, first page, name and fields, which the checks below still accept. But a
-- campaign's deletion, and a system's (which deletes its campaigns), fails on the campaign reference while a campaign
-- going has artifacts; and its Ontology.delete ignores artifacts, so it removes an approved record type that artifacts
-- still use. Before rolling back, delete the artifacts (versions first):
--   delete from record_version;
--   delete from record where campaign_id is not null;
-- Structure only: no trigger, procedure or row-level security.
alter table record alter column version_id drop not null;
alter table record alter column ordinal drop not null;
alter table record alter column first_page drop not null;
alter table record alter column name drop not null;
alter table record alter column fields drop not null;
alter table record add column campaign_id uuid;
alter table record add constraint record_campaign_fkey foreign key (owner_id, campaign_id)
    references campaign (owner_id, id);
alter table record add constraint record_origin_check check ((version_id is null) <> (campaign_id is null));
alter table record add constraint record_extracted_check check (
    (version_id is null and ordinal is null and first_page is null and name is null and fields is null)
    or (version_id is not null and ordinal is not null and first_page is not null and name is not null
        and fields is not null));

create index record_owner_campaign_type on record (owner_id, campaign_id, type);
create index record_owner_campaign_id on record (owner_id, campaign_id, id);

create table record_version (
    record_id  uuid        not null,
    owner_id   uuid        not null,
    number     integer     not null check (number >= 1),
    name       bytea       not null,
    fields     bytea       not null,
    content    bytea,
    created_at timestamptz not null,
    constraint record_version_pkey primary key (record_id, number),
    constraint record_version_record_fkey foreign key (owner_id, record_id) references record (owner_id, id)
);
