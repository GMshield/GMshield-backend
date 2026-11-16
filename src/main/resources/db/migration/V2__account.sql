-- Story 1.9 (AD-19, AD-20): accounts signed in with Google. The username, the email address and the
-- Google id are stored only encrypted through the vault (bound to entity "account", the field, the row
-- and the account itself as owner), each beside a keyed fingerprint for exact lookups. Tier, state,
-- the fingerprints, the confirmed flag and the seal version are sealed: the backend checks the seal on
-- every load. Ids, dates, the tier, the state and the terms version stay readable.
create table account (
    id                    uuid        primary key,
    tier                  text        not null,
    state                 text        not null check (state in ('unconfirmed', 'active', 'blocked', 'deleting')),
    confirmed             boolean     not null,
    google_id             bytea       not null,
    google_id_fingerprint bytea       not null,
    email                 bytea       not null,
    email_fingerprint     bytea       not null,
    username              bytea       not null,
    username_fingerprint  bytea       not null,
    terms_version         text        not null,
    terms_accepted_at     timestamptz not null,
    seal_version          integer     not null check (seal_version > 0),
    seal                  bytea       not null,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

-- One account per Google identity, and one per username whatever its case (the fingerprint is taken
-- over the NFC lower-case form).
create unique index account_google_id_fingerprint_key on account (google_id_fingerprint);
create unique index account_username_fingerprint_key on account (username_fingerprint);
