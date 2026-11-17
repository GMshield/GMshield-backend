-- Story 1.15 (AD-14, AD-15): a file a worker sent back for a claimed job, before the job's handler adopts it. The
-- file's pieces are in the encrypted file store under the job's owner and this id (minted by the backend); the row
-- holds no content, no name and no hash of the file. Not a library table, like job: no @TenantId. A row is written
-- as 'writing' before the first piece and becomes 'stored' once its last piece is written while the claim still
-- lives. The handler adopts an output by deleting its row in the transaction that records the job's report; the
-- jobs sweep removes the files and the row of every output still here once its job has ended. Times come from the
-- application. Structure only: no trigger, procedure or row-level security.
create table job_output (
    id          uuid        primary key,
    job_id      uuid        not null references job (id),
    owner_id    uuid        not null,
    claim_id    uuid        not null,
    state       varchar(16) not null check (state in ('writing', 'stored')),
    size_bytes  bigint      not null default 0 check (size_bytes >= 0),
    piece_count integer     not null default 0 check (piece_count >= 0),
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);

-- A claim's total, a job's outputs, and the sweep's join on ended jobs.
create index job_output_job_claim on job_output (job_id, claim_id);
