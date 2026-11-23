-- Story 4.1: the ontology's record types, per system and owner. A record type types the records of its system (Story
-- 4.2 onward). Every system has the eight core types, named by a fixed plaintext key in `core` (null for a type the GM
-- approved, from Story 4.4): rule, spell, item, creature, character_sheet, encounter, dungeon, note. The backend seeds
-- them in the same transaction as a new system, and once at start for every system from before V34 (a missing core key
-- of a ready system is added; unique (owner_id, system_id, core) makes two backends filling at once safe: the second
-- insert fails and that system is filled at the next start).
-- The name is stored only encrypted through the vault, bound to the entity "record_type", the field "name", the row and
-- the owner; a core type's name is its key in English ("character sheet"). The name's keyed fingerprint (of its NFC
-- lower-case, trimmed form) is unique per owner and system, so an approved type can never take a core type's name.
-- A record type names its system with the owner, so it can never point at another owner's system; the system's deletion
-- removes its record types first (the knowledge module's deletion handler).
-- Additive: the previous release neither reads nor writes record_type.
-- Structure only: no trigger, procedure or row-level security.
create table record_type (
    id               uuid        primary key,
    owner_id         uuid        not null,
    system_id        uuid        not null,
    core             text,
    name             bytea       not null,
    name_fingerprint bytea       not null,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    constraint record_type_core_check check (core in ('rule', 'spell', 'item', 'creature', 'character_sheet',
                                                      'encounter', 'dungeon', 'note')),
    constraint record_type_owner_id_key unique (owner_id, id),
    constraint record_type_system_fkey foreign key (owner_id, system_id) references system (owner_id, id),
    constraint record_type_owner_system_core_key unique (owner_id, system_id, core),
    constraint record_type_owner_system_name_fingerprint_key unique (owner_id, system_id, name_fingerprint)
);
