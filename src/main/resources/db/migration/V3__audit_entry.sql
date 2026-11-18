-- Story 1.10: the audit log, append-only. The audit module only ever inserts; refusing an update or a
-- delete in the database itself comes with Story 9.1, and the reader with Epic 9. A row holds ids, the
-- time, the action, the trace id and a small JSON of safe details: never a token, a hash, an email
-- address, a username or library content.
create table audit_entry (
    id         uuid        primary key,
    at         timestamptz not null,
    action     text        not null check (action ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    account_id uuid,
    trace_id   text        not null check (trace_id ~ '^[0-9a-f]{32}$'),
    details    jsonb       not null default '{}'::jsonb check (jsonb_typeof(details) = 'object')
);

create index audit_entry_account_at on audit_entry (account_id, at);
create index audit_entry_at on audit_entry (at);
