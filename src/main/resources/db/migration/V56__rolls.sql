-- Story 6.4 (part A, AD-31): the dice rolls made in a Shield are stored, so the GM can look back at them on any device.
-- The browser draws the dice (Story 6.3); the backend never rolls, it only checks a roll against the same rules and
-- keeps it once.
-- roll: one row per stored roll of a Shield, with the Shield's owner. roll_key is the key the browser made for the roll
-- (crypto.randomUUID()), unique per owner, so a roll sent again is found instead of stored twice. content is one vault
-- ciphertext bound to the entity "roll", the field "content", the row and the owner, holding the normalised formula,
-- the results and the optional label together. rolled_at is the browser's time of the roll, plain, for the order of
-- the log (newest first, then by id).
-- Keys: (owner, id) as every library key; (owner, roll_key) unique; the Shield's (owner, shield) has no cascade (as
-- record_shield_fkey, V54): a Shield's, a campaign's or a system's deletion removes the Shield's rolls before its row.
-- The index on (owner, shield, rolled_at desc, id desc) serves the log, newest first.
-- Rolling back past V56: the previous backend release neither reads nor writes this table, but deleting a Shield, a
-- campaign or a system that holds rolls then fails on roll_shield_fkey. Before running that release, drop the table:
--   drop table roll;
-- WARNING: that drop deletes every stored roll of every account for good. Take a backup of the table first if the
-- rolls must come back.
-- Structure only: no trigger, procedure or row-level security.
create table roll (
    id          uuid        primary key,
    owner_id    uuid        not null,
    shield_id   uuid        not null,
    roll_key    uuid        not null,
    content     bytea       not null,
    rolled_at   timestamptz not null,
    created_at  timestamptz not null default now(),
    constraint roll_owner_id_key unique (owner_id, id),
    constraint roll_owner_key_key unique (owner_id, roll_key),
    constraint roll_shield_fkey foreign key (owner_id, shield_id) references shield (owner_id, id)
);

create index roll_owner_shield_rolled on roll (owner_id, shield_id, rolled_at desc, id desc);
