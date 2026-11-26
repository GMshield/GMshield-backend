-- Story 4.5 (part B): the GM deletes a record. The row stays as a tombstone: record.deleted_at is the time of the GM's
-- delete, and record.point_removed_at the time the removal of its index point finished (set at once for a record that
-- was never indexed). A deleted row is never shown again: every public read, every index input and every stored
-- citation leaves it out, and only an answer's read of the records its search found still serves it (the index may still
-- hold its point), so that answer does not fail. Its name and fields stay encrypted where they are and are no longer read; its (type key, name fingerprint),
-- and its extracted key when it was corrected, still match the record a reprocess reads again, so accepting that
-- version tombstones the new record too.
-- record_removal is the input of one job of kind 'index.record_removal': the record whose point goes, within its
-- version (the point's source). Like index_removal (V15) it is a library table whose every key includes the owner, and
-- it has no reference to the record or the version, so it outlives a version that is deleted (the worker then deletes
-- nothing, and the completion marks nothing).
-- Rolling back past V42 is not safe while a tombstone exists: the previous backend release reads a deleted record as a
-- live one (listed, counted, searched, indexed again) and runs no 'index.record_removal' job. Before rolling back, wait
-- until every tombstone has point_removed_at (or remove its version's points from the index), end any
-- 'index.record_removal' job still waiting or running, then delete the tombstone rows (record_passage links first) and
-- the record_removal rows.
-- Structure only: no trigger, procedure or row-level security.
alter table record add column deleted_at timestamptz;
alter table record add column point_removed_at timestamptz;
alter table record add constraint record_point_removed_check check (point_removed_at is null or deleted_at is not null);

create table record_removal (
    job_id     uuid primary key,
    owner_id   uuid not null,
    version_id uuid not null,
    record_id  uuid not null,
    constraint record_removal_owner_job_key unique (owner_id, job_id)
);
