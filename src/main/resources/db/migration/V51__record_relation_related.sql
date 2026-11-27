-- Story 5.5 (part B): the GM relates an artifact to records of its campaign's scope with "related to" (FR-20).
-- record_relation (V50) takes a second kind, 'related': a link from an artifact (from_record_id) to a live extracted
-- record of any tier of the campaign's resolved scope, or to another live artifact of the same campaign
-- (to_record_id). A relation has no direction: it is stored once, from the artifact whose page made it, and read from
-- both sides; relating B to A when A is related to B already stores nothing. The service keeps at most 50 relations per
-- artifact, whichever side it is on (409 artifact.relations_full).
-- Part A's rule stays: an artifact overrides at most one record. Its unique key on (from_record_id, kind) becomes a
-- partial unique index on from_record_id where kind = 'overrides', since an artifact has many 'related' links; a new
-- unique key on (from_record_id, to_record_id, kind) keeps one link of a kind between the same two records in the same
-- direction. The other keys of V50 (both foreign keys on delete cascade, the self check) apply to 'related' as they
-- are: a hard-deleted artifact or record takes its relations with it.
-- Rolling back past V51 is safe for the previous backend release, which reads and writes the 'overrides' kind only (its
-- reads filter by kind, and its single override is still unique). To restore V50's keys, remove the new kind first:
--   delete from record_relation where kind = 'related';
--   alter table record_relation drop constraint record_relation_from_to_kind_key;
--   drop index record_relation_overrides_from_key;
--   alter table record_relation add constraint record_relation_from_kind_key unique (from_record_id, kind);
--   alter table record_relation drop constraint record_relation_kind_check;
--   alter table record_relation add constraint record_relation_kind_check check (kind in ('overrides'));
-- Structure only: no trigger, procedure or row-level security.
alter table record_relation drop constraint record_relation_kind_check;
alter table record_relation add constraint record_relation_kind_check check (kind in ('overrides', 'related'));

alter table record_relation drop constraint record_relation_from_kind_key;
create unique index record_relation_overrides_from_key on record_relation (from_record_id) where kind = 'overrides';
alter table record_relation add constraint record_relation_from_to_kind_key
    unique (from_record_id, to_record_id, kind);
