-- Test mode only: application-test-mode.yaml alone adds this location, so production never has the
-- table. The NFR-1 release gate stores its known text here through the vault (Story 1.7).
-- Repeatable (R__), so its version never collides with the production migrations.
create table if not exists vault_probe (
    id               uuid        primary key,
    owner_id         uuid        not null,
    text_value       bytea       not null,
    text_fingerprint bytea       not null,
    file_id          uuid        not null,
    created_at       timestamptz not null default now()
);
