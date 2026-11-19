-- Indexing at worker start (2026-11-19): how many times the retry sweep gave an index change a new job. When the job of
-- an index_change row ends 'failed', the sweep (every 10 min) creates a new 'index.change' job for the same ordinal
-- range, moves the row to it (job_id is the key; the failed job row stays as history) and adds 1 here, at most 3 times.
-- A new set of index changes (an accept, or a reconcile) starts at 0. Additive: the previous release, which neither
-- reads nor writes it, keeps working on this schema (its inserts take the default). Structure only: no trigger,
-- procedure or row-level security.
alter table index_change
    add column retries smallint not null default 0
        constraint index_change_retries_check check (retries between 0 and 3);
