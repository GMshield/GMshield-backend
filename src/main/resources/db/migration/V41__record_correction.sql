-- Story 4.5 (part A): the GM corrects a record. A record gains its version (Hibernate's: each correction names it and
-- raises it, and a correction of a row whose version moved meanwhile is refused), the time of its last correction
-- (null for a record never corrected) and the match key its extraction produced, kept from its first correction on:
-- the type key and the name's keyed fingerprint the model read (the fingerprint is null for a record stored before
-- V37). Its name, fields and name fingerprint are rewritten in place by a correction, still encrypted and bound to the
-- row; the id, the links to passages, the ordinal and the first page never change.
-- material_version.reprocessed_from names the accepted version a reprocess read again (Story 3.4), so the new
-- version's acceptance carries that version's corrections over. It is set once, at the new version's insert; deleting
-- the version it names (a material's deletion removes every version) clears it rather than refusing.
-- Rolling back past V41 is safe: the previous backend release neither reads nor writes these columns (a record it
-- stores gets version 0 and no correction; a version it stores, no reprocessed_from), and reads a corrected record's
-- name and fields as the record's own. Only the carry-over of corrections at an acceptance is lost meanwhile.
-- Structure only: no trigger, procedure or row-level security.
alter table record add column version bigint not null default 0;
alter table record add column corrected_at timestamptz;
alter table record add column extracted_type varchar(32);
alter table record add column extracted_name_fingerprint bytea;
alter table record add constraint record_extracted_type_check check (extracted_type ~ '^[a-z][a-z0-9_]{0,31}$');
alter table record add constraint record_correction_check check (
    (corrected_at is null and extracted_type is null and extracted_name_fingerprint is null)
    or (corrected_at is not null and extracted_type is not null));

alter table material_version add column reprocessed_from uuid;
alter table material_version add constraint material_version_reprocessed_from_fkey
    foreign key (owner_id, reprocessed_from) references material_version (owner_id, id)
    on delete set null (reprocessed_from);

-- The key's lookups (a version's deletion clears the versions that name it) use this index.
create index material_version_owner_reprocessed_from on material_version (owner_id, reprocessed_from)
    where reprocessed_from is not null;
