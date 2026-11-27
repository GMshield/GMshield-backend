-- Story 6.2 (part A): the GM writes notes in a Shield. A note is an artifact of record type 'note' (a `record` row of
-- its campaign with its versions in `record_version`, as Story 5.1 writes them) that also names the Shield holding it.
-- record.shield_id: the Shield holding the note; null for every other artifact, for every extracted record, and for the
-- 'note' artifacts written before V54, which no Shield holds. The reference includes the owner, as every library key
-- does, and has no cascade: a campaign's or a system's deletion removes the campaign's artifacts, its notes with them,
-- before its Shields. The `knowledge` module owns the column; the service checks that the Shield is one of the note's
-- own campaign. The check keeps a held note an artifact of type 'note': its edits never retype it.
-- The partial index on (owner, shield, id) serves a Shield's notes, newest first.
-- Rolling back past V54 is safe for reads: the previous backend release neither reads nor writes this column (its
-- inserts leave it null), so held notes read as plain campaign notes there. But that release's Shield deletion fails
-- on record_shield_fkey for a Shield that holds notes. Before running it, either keep the notes as campaign notes:
--   alter table record drop constraint record_shield_check, drop constraint record_shield_fkey, drop column shield_id;
-- (the index goes with the column) or keep this migration in place and delete no Shield that holds notes.
-- Structure only: no trigger, procedure or row-level security.
alter table record add column shield_id uuid;
alter table record add constraint record_shield_fkey foreign key (owner_id, shield_id) references shield (owner_id, id);
alter table record add constraint record_shield_check check (
    shield_id is null or (campaign_id is not null and type = 'note'));

create index record_owner_shield on record (owner_id, shield_id, id) where shield_id is not null;
