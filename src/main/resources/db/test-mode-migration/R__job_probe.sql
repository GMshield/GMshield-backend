-- Test mode only (Story 1.12): what a probe job does (how long it works, how many attempts fail), beside its job.
-- The probe job kind drives the jobs tests until Story 1.15 adds the first real kind; no public route creates one.
create table if not exists job_probe (
    job_id     uuid    primary key,
    work_ms    integer not null check (work_ms >= 0),
    fail_times integer not null check (fail_times >= 0)
);
