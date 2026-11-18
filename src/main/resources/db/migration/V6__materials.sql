-- Story 1.13 (AD-11, AD-16, AD-19): a material, its versions and the resumable upload of a version's file.
-- material and material_version are library tables: each row carries its owner, filled and filtered by Hibernate
-- @TenantId, and every key and reference includes it. A material's name is stored only encrypted through the vault,
-- bound to the entity, the field "name", the row and the owner.
-- upload is a working table of the tus protocol, like job: no content, no name and no hash of the file; the file's
-- pieces are in the encrypted file store. Its version is named with the owner, so it can never point at another
-- owner's version. Times come from the application. Structure only: no trigger, procedure or row-level security.
create table material (
    id         uuid        primary key,
    owner_id   uuid        not null,
    name       bytea       not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint material_owner_id_key unique (owner_id, id)
);

create table material_version (
    id          uuid        primary key,
    owner_id    uuid        not null,
    material_id uuid        not null,
    state       varchar(16) not null
                check (state in ('uploading', 'waiting', 'processing', 'needs_review', 'ready', 'superseded', 'failed',
                                 'discarded')),
    format      varchar(16)
                check (format in ('pdf', 'txt', 'markdown', 'jpeg', 'png', 'webp', 'tiff', 'bmp', 'heic')),
    size_bytes  bigint      check (size_bytes between 1 and 2147483648),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint material_version_owner_id_key unique (owner_id, id),
    constraint material_version_material_fkey foreign key (owner_id, material_id) references material (owner_id, id)
);

create index material_version_owner_material on material_version (owner_id, material_id, created_at);

create table upload (
    id            uuid        primary key,
    owner_id      uuid        not null,
    version_id    uuid        not null,
    length        bigint      not null check (length between 1 and 2147483648),
    upload_offset bigint      not null default 0 check (upload_offset between 0 and length),
    piece_count   integer     not null default 0 check (piece_count >= 0),
    format        varchar(16)
                  check (format in ('pdf', 'txt', 'markdown', 'jpeg', 'png', 'webp', 'tiff', 'bmp', 'heic')),
    expires_at    timestamptz not null,
    busy_token    uuid,
    busy_until    timestamptz,
    state         varchar(16) not null check (state in ('open', 'complete', 'removing')),
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    constraint upload_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

-- What the sweep looks at: open uploads past their expiry, and removals to finish.
create index upload_state_expires_at on upload (state, expires_at);
create index upload_owner_version on upload (owner_id, version_id);
