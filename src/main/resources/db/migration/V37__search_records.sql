-- Story 4.3 (part B): a campaign's library search also finds records.
-- record.name_fingerprint is the vault's keyed fingerprint of the record's name (entity "record", field "name") in its
-- unique form (trimmed, NFC, lower case in the root locale), set when the record is stored, so a search finds the records
-- whose name equals its query by comparing fingerprints, never by text search in SQL (AD-19). A record stored before
-- V37 keeps null and never matches exactly (no released version has records); reprocessing its material makes a new
-- version, whose records get a fingerprint.
-- search.record_type is a search's record-type filter: a core key (records store core keys), or null for none.
-- search_result holds either a passage or a record (exactly one of passage_id and record_id), each by value: the record
-- is re-checked against the campaign's current scope each time the search is read, as a passage is.
-- The previous backend release reads search_result.passage_id as never null: rolling back past V37 is safe only while no
-- result holds a record (delete those rows first: `delete from search_result where record_id is not null`).
-- Structure only: no trigger, procedure or row-level security.
alter table record add column name_fingerprint bytea;

create index record_owner_name_fingerprint on record (owner_id, name_fingerprint);

alter table search add column record_type varchar(32);
alter table search add constraint search_record_type_check check (record_type in ('rule', 'spell', 'item',
    'creature', 'character_sheet', 'encounter', 'dungeon', 'note'));

alter table search_result add column record_id uuid;
alter table search_result alter column passage_id drop not null;
alter table search_result add constraint search_result_item_check check ((passage_id is null) <> (record_id is null));
alter table search_result add constraint search_result_record_key unique (owner_id, search_id, record_id);
