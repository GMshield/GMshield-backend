-- Story 4.3 (part C, AD-11): an answer may cite a record, and a cited record is its own source reference of the turn,
-- stored by value like a passage's: its version, material, page, section path and passages (its passages on its first
-- page) in the existing columns, plus the record's id, its core type's key, and its name and fields as they were when
-- the answer was written. Records are hard-deleted and re-minted when their material is reprocessed, so the reference
-- never reads the record again: record_id is kept by value, with no reference to record.
-- record_name and record_fields (a JSON array of {name, value}, in order) are stored only encrypted through the vault,
-- bound to the entity "turn_reference", the fields "record_name" and "record_fields", the row and the owner;
-- record_type names no library content and stays plain. The four are set together on a record's reference and are all
-- null on a passage's.
-- The previous backend release neither reads nor writes these columns: after a rollback past V38 a record's reference
-- reads as a passage reference (its material, page and passages), without its name and fields. But a turn's stored
-- citation event that names a recordId and no passageId breaks that release's TurnEvents.data: once a record has been
-- cited, rolling back needs those turns' record citation events removed first, and is unsafe otherwise.
-- Structure only: no trigger, procedure or row-level security.
alter table turn_reference add column record_id uuid;
alter table turn_reference add column record_type varchar(32);
alter table turn_reference add column record_name bytea;
alter table turn_reference add column record_fields bytea;
alter table turn_reference add constraint turn_reference_record_check check (
    (record_id is null and record_type is null and record_name is null and record_fields is null)
    or (record_id is not null and record_type is not null and record_name is not null and record_fields is not null));
alter table turn_reference add constraint turn_reference_record_type_check check (record_type in ('rule', 'spell',
    'item', 'creature', 'character_sheet', 'encounter', 'dungeon', 'note'));
