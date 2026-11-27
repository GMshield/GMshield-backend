-- Story 6.1 (part A): a Shield (Escudo do Mestre) is the GM's private space in a campaign, for notes (6.2) and dice
-- (6.3, 6.4). Every campaign has at least one.
-- shield: one row per Shield of a campaign, with the campaign's owner. name is a vault ciphertext bound to the entity
-- "shield", the field "name", the row and the owner; name_fingerprint is the keyed fingerprint of its trimmed, NFC,
-- lower-case form, unique within the campaign (as a campaign's name within its system). The first Shield of a campaign
-- is stored unnamed (name and name_fingerprint null: the frontend shows it as "Escudo do Mestre" / "GM's Shield" in the
-- GM's language) until the GM renames it. version starts at 0 and rises by one with each rename (Hibernate @Version).
-- Keys: (owner, id) as every library key; the campaign's (owner, campaign) has no cascade, as every campaign key: a
-- campaign's or a system's deletion removes the campaign's Shields before the campaign row goes. The unique key on
-- (campaign, name_fingerprint) ignores the unnamed Shield (null), and the partial unique index lets each campaign have
-- one unnamed Shield at most, so two lists that create a missing first Shield at once cannot both insert one.
-- The index on (owner, campaign, id) serves the list of a campaign's Shields, oldest first.
-- Then every existing campaign, deleting ones included, gets its unnamed first Shield (uuidv7(), PostgreSQL 18).
-- Rolling back past V53: the previous backend release neither reads nor writes this table, but deleting a campaign
-- or a system then fails on shield_campaign_fkey. Before running that release, drop the table:
--   drop table shield;
-- WARNING: that drop deletes every Shield of every account for good, the typed names with them; a later run of V53
-- gives each campaign only a new, unnamed Shield. Take a backup of the table first if the Shields must come back.
-- Structure only: no trigger, procedure or row-level security.
create table shield (
    id               uuid        primary key,
    owner_id         uuid        not null,
    campaign_id      uuid        not null,
    name             bytea,
    name_fingerprint bytea,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    constraint shield_owner_id_key unique (owner_id, id),
    constraint shield_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id),
    constraint shield_campaign_name_fingerprint_key unique (campaign_id, name_fingerprint),
    constraint shield_name_check check ((name is null) = (name_fingerprint is null))
);

create unique index shield_campaign_unnamed_key on shield (campaign_id) where name is null;

create index shield_owner_campaign on shield (owner_id, campaign_id, id);

insert into shield (id, owner_id, campaign_id, version, created_at)
select uuidv7(), c.owner_id, c.id, 0, now() from campaign c;
