-- Story 2.5 (AD-11, AD-25): the source references of a completed turn, stored by value when its answer job completes,
-- so a source removed later still shows, marked removed. The previous backend release neither reads nor writes it.
-- turn_reference is one numbered reference (1..n, by first citation) of a turn: one distinct place (version, section
-- path, page) that the answer cited, with the ids of the cited passages at that place. The material's name and the
-- section path are stored only encrypted through the vault, bound to the entity "turn_reference", the fields
-- "material_name" and "section_path", the row and the owner. version_id and material_id are kept by value, with no
-- reference to material_version or material: those rows may be removed, and the reference then reads as removed. A
-- library table: each row carries its owner, filled and filtered by Hibernate @TenantId, and every key and reference
-- includes it, as in V17. Structure only: no trigger, procedure or row-level security.
create table turn_reference (
    id            uuid        primary key,
    owner_id      uuid        not null,
    turn_id       uuid        not null,
    number        integer     not null check (number >= 1),
    version_id    uuid        not null,
    material_id   uuid        not null,
    page          integer     not null check (page >= 1),
    passage_ids   uuid[]      not null check (cardinality(passage_ids) >= 1),
    material_name bytea       not null,
    section_path  bytea       not null,
    created_at    timestamptz not null,
    constraint turn_reference_owner_id_key unique (owner_id, id),
    constraint turn_reference_turn_number_key unique (owner_id, turn_id, number),
    constraint turn_reference_turn_fkey foreign key (owner_id, turn_id) references turn (owner_id, id)
);
