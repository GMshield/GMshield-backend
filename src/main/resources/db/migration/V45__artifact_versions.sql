-- Story 5.2 (part A): the GM edits an artifact and keeps its versions. An edit or a restore writes the next numbered
-- version of record_version and never changes an earlier one. Each version holds its own record type's key
-- (record_type), so restoring an earlier version brings back the type it had, not the newest one's. record.type stays
-- where every reader looks (the artifacts tab, "Personagens", scope, the type-in-use guard): it always mirrors the
-- newest version's, written in the same transaction as that version.
-- The column is added, filled from each version's record (every version until now was number 1, of its record's type),
-- then made NOT NULL. The text check is V40's, as on record.type.
-- Rolling back past V45 is safe once the NOT NULL is dropped: the previous backend release never reads record_type,
-- and it reads the newest version of an artifact by its number, as this release does, so an edited artifact still
-- opens and lists with its newest content and type. It does insert versions (a new artifact) without record_type, so
-- with the backend stopped, run before starting it:
--   alter table record_version alter column record_type drop not null;
-- and before deploying this release again, fill the versions it wrote:
--   update record_version v set record_type = r.type from record r where r.id = v.record_id and v.record_type is null;
--   alter table record_version alter column record_type set not null;
-- Structure only: no trigger, procedure or row-level security.
alter table record_version add column record_type text;

update record_version v set record_type = r.type from record r where r.id = v.record_id and r.owner_id = v.owner_id;

alter table record_version alter column record_type set not null;
alter table record_version add constraint record_version_record_type_check check (
    record_type ~ '^[a-z][a-z0-9_]{0,31}$');
