-- Story 4.4 (part A): the record types and terms a material proposes, and the GM's decisions on them.
-- A records step's report may carry proposals (a record type or a term, each with up to three examples quoted from its
-- passages, and a term its quoted definition). The backend stores one proposal per system the material counts under
-- (its systems, or its campaign's system): each system decides on its own. A proposal joins nothing by itself: only an
-- approval creates a record type or a term, which keeps its origin (proposal_id, and through it the material). Merging a
-- term proposal into a term adds its name as an alias of that term; merging a type proposal into a type only closes it.
-- Name, definition, examples and aliases are stored only encrypted through the vault, each bound to its entity
-- ("proposal", "term", "term_alias"), its field, the row and the owner. Every name's keyed fingerprint is the record
-- type's (entity "record_type", field "name", of the name's unique form), so one system's types, terms and aliases
-- share one name space: each table keeps its names unique per system, and the service checks the three together (and
-- the core names in pt-BR and en) under a lock on the system's ontology.
-- A proposal is unique per (owner, system, material, name), whatever its state, so a rejected one is never raised again
-- when its material is reprocessed. Its version is the one whose records step proposed it: clearing that version
-- (a restart, a reprocess, a discard) removes its pending proposals and detaches the decided ones; deleting its
-- material removes those that were not approved and detaches the approved ones (their origin then has no material).
-- record_type gains `key`, a stable slug of an approved type's name that never changes (a core type's key is its core
-- key), unique per system, and `proposal_id`, the approving proposal (null exactly for a core type).
-- Library tables: each row carries its owner, filled and filtered by Hibernate @TenantId, and every key and reference
-- includes it, as in V4. Nothing cascades: a system's deletion removes aliases, terms, approved types and proposals in
-- that order (the knowledge module's deletion handler).
-- The previous backend release inserts core record types without a key or a proposal: a core type's key may stay null
-- (read as its core key), so V39 keeps that release working. Structure only: no trigger, procedure or row-level
-- security.
create table proposal (
    id               uuid        primary key,
    owner_id         uuid        not null,
    system_id        uuid        not null,
    material_id      uuid,
    version_id       uuid,
    kind             varchar(16) not null,
    name             bytea       not null,
    name_fingerprint bytea       not null,
    definition       bytea,
    examples         bytea       not null,
    state            varchar(16) not null,
    decided_at       timestamptz,
    version          bigint      not null default 0,
    created_at       timestamptz not null,
    constraint proposal_kind_check check (kind in ('record_type', 'term')),
    constraint proposal_state_check check (state in ('pending', 'approved', 'merged', 'rejected')),
    constraint proposal_decided_check check ((state = 'pending') = (decided_at is null)),
    constraint proposal_version_material_check check (version_id is null or material_id is not null),
    constraint proposal_term_definition_check check (kind <> 'term' or definition is not null),
    constraint proposal_owner_id_key unique (owner_id, id),
    constraint proposal_owner_system_id_key unique (owner_id, system_id, id),
    constraint proposal_system_fkey foreign key (owner_id, system_id) references system (owner_id, id),
    constraint proposal_material_fkey foreign key (owner_id, material_id) references material (owner_id, id),
    constraint proposal_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id),
    constraint proposal_owner_system_material_name_key unique (owner_id, system_id, material_id, name_fingerprint)
);

create index proposal_owner_system_state on proposal (owner_id, system_id, state, id);
create index proposal_owner_material on proposal (owner_id, material_id, id);
create index proposal_owner_version on proposal (owner_id, version_id);

alter table record_type add column key varchar(32);
update record_type set key = core;
alter table record_type add column proposal_id uuid;
alter table record_type add constraint record_type_key_check check (
    (core is null and key is not null) or (core is not null and (key is null or key = core)));
alter table record_type add constraint record_type_origin_check check ((core is null) = (proposal_id is not null));
alter table record_type add constraint record_type_owner_system_key_key unique (owner_id, system_id, key);
alter table record_type add constraint record_type_proposal_fkey foreign key (owner_id, system_id, proposal_id)
    references proposal (owner_id, system_id, id);

create table term (
    id               uuid        primary key,
    owner_id         uuid        not null,
    system_id        uuid        not null,
    name             bytea       not null,
    name_fingerprint bytea       not null,
    definition       bytea       not null,
    proposal_id      uuid        not null,
    version          bigint      not null default 0,
    created_at       timestamptz not null,
    constraint term_owner_id_key unique (owner_id, id),
    constraint term_owner_system_id_key unique (owner_id, system_id, id),
    constraint term_system_fkey foreign key (owner_id, system_id) references system (owner_id, id),
    constraint term_proposal_fkey foreign key (owner_id, system_id, proposal_id)
        references proposal (owner_id, system_id, id),
    constraint term_owner_system_name_fingerprint_key unique (owner_id, system_id, name_fingerprint)
);

create table term_alias (
    id               uuid        primary key,
    owner_id         uuid        not null,
    system_id        uuid        not null,
    term_id          uuid        not null,
    name             bytea       not null,
    name_fingerprint bytea       not null,
    proposal_id      uuid,
    created_at       timestamptz not null,
    constraint term_alias_owner_id_key unique (owner_id, id),
    constraint term_alias_term_fkey foreign key (owner_id, system_id, term_id) references term (owner_id, system_id, id),
    constraint term_alias_proposal_fkey foreign key (owner_id, system_id, proposal_id)
        references proposal (owner_id, system_id, id),
    constraint term_alias_owner_system_name_fingerprint_key unique (owner_id, system_id, name_fingerprint)
);

create index term_alias_owner_term on term_alias (owner_id, term_id);
