-- Story 1.11 (AD-11, AD-19): the first library tables, a game system and a campaign. Each row carries its
-- owner (the account), filled and filtered by Hibernate @TenantId from the owner of the request; every
-- uniqueness includes it. Names are stored only encrypted through the vault, bound to the entity, the field
-- "name", the row and the owner. A system's name has a keyed fingerprint of its NFC lower-case, trimmed form,
-- unique per owner. A campaign names its system with the owner as well, so it can never point at another
-- owner's system. Structure only: no trigger, procedure or row-level security.
create table system (
    id               uuid        primary key,
    owner_id         uuid        not null,
    name             bytea       not null,
    name_fingerprint bytea       not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint system_owner_id_key unique (owner_id, id),
    constraint system_owner_name_fingerprint_key unique (owner_id, name_fingerprint)
);

create table campaign (
    id         uuid        primary key,
    owner_id   uuid        not null,
    system_id  uuid        not null,
    name       bytea       not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint campaign_owner_id_key unique (owner_id, id),
    constraint campaign_system_fkey foreign key (owner_id, system_id) references system (owner_id, id)
);

create index campaign_owner_created_at on campaign (owner_id, created_at, id);
create index campaign_owner_system on campaign (owner_id, system_id);
