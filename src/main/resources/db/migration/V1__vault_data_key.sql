-- Story 1.7 (AD-19): one data key per owner and version, wrapped (AES-256-GCM) by the master key
-- under AAD that names the owner and the version. The key itself is never stored.
create table vault_data_key (
    owner_id   uuid        not null,
    version    integer     not null check (version > 0),
    wrapped    bytea       not null,
    created_at timestamptz not null default now(),
    primary key (owner_id, version)
);
