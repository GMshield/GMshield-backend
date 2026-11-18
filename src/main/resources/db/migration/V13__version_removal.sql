-- Story 1.20 (AD-15, AD-22): a discarded material version is removed by a recorded process that resumes after a
-- restart. The previous backend release writes none of it and keeps working: its inserts take the default, and it never
-- reads the column.
-- material_version.removal_pending is set, with the state 'discarded', in the transaction that discards the version
-- (the GM's action). The removal then runs as the owner, in parts each safe to repeat: the version's page views,
-- passages, outline, page layers and outcomes, then the pieces of every version file, then the version_file rows and
-- the complete raw upload, and last the flag is cleared. The removal sweep finds the flagged versions across owners by
-- this column alone (ids, never content) and finishes each one as its owner. The ingestion_step rows stay: they hold
-- no content. The version row stays too, 'discarded'; its material is removed only by Story 3.4.
-- Structure only: no trigger, procedure or row-level security.
alter table material_version
    add column removal_pending boolean not null default false;

create index material_version_removal_pending on material_version (updated_at, id) where removal_pending;
