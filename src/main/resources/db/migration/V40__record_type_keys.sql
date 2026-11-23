-- Story 4.4 (part B): records of a type the GM approved are extracted. A record's type, a search's record-type filter
-- and a cited record's type hold a record type's key: a core key, or the key of a type approved in a system (V39's
-- record_type.key, a slug that never changes). The core-only checks of V35 (record), V37 (search) and V38
-- (turn_reference) become the key pattern: a lower-case letter, then up to 31 lower-case letters, digits or
-- underscores. Which keys a system has is checked by the application, never here: a record belongs to a version, and a
-- version may count under several systems.
-- Rolling back past V40 is safe: the previous backend release (Story 4.4 part A) reads a record whose key is not a core
-- key as a passage (it is neither listed, counted, searched nor cited), and its own writes stay within the core keys.
-- Rows written with another key would only fail the restored core-only checks if those were re-added; a rollback does
-- not re-add them.
-- Structure only: no trigger, procedure or row-level security.
alter table record drop constraint record_type_check;
alter table record add constraint record_type_check check (type ~ '^[a-z][a-z0-9_]{0,31}$');

alter table search drop constraint search_record_type_check;
alter table search add constraint search_record_type_check check (record_type ~ '^[a-z][a-z0-9_]{0,31}$');

alter table turn_reference drop constraint turn_reference_record_type_check;
alter table turn_reference add constraint turn_reference_record_type_check check (
    record_type ~ '^[a-z][a-z0-9_]{0,31}$');
