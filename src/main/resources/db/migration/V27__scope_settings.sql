-- Story 3.5 (AD-9, AD-10, AD-11): a campaign's scope settings are stored, and a question freezes them on its turn.
-- scope_setting is one campaign's three tier switches (campaign, system, other), model knowledge and a version
-- (Hibernate @Version: a change that names an earlier one is refused, and nothing changes). A campaign with no row
-- reads as the starting settings (campaign and system on, other and model knowledge off) at version 0, and the first
-- change inserts the row at version 1, so library's campaign creation writes nothing here. scope_material_off is one
-- material switched off in one campaign's scope: a material is on unless it is listed. material_id has no reference:
-- a material that is gone is ignored when resolving and dropped on the next change. Both are library tables: each row
-- carries its owner, filled and filtered by Hibernate @TenantId, and every key and reference includes it.
-- turn.other_tier and turn_material_off freeze the other tier's switch and the materials switched off on a turn, beside
-- the switches V17 froze, so a change made while a turn is answered applies from the next question; a turn from before
-- this release had no other tier and no material off (the defaults).
-- Additive: the previous release neither reads nor writes these tables or this column (its turns take the default).
-- After a rollback the stored settings are ignored and every campaign reads as the starting settings again.
-- Scope settings never reach the index (AD-10). Structure only: no trigger, procedure or row-level security.
create table scope_setting (
    owner_id        uuid        not null,
    campaign_id     uuid        not null,
    campaign_tier   boolean     not null,
    system_tier     boolean     not null,
    other_tier      boolean     not null,
    model_knowledge boolean     not null,
    version         bigint      not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    constraint scope_setting_pkey primary key (owner_id, campaign_id),
    constraint scope_setting_campaign_id_key unique (campaign_id),
    constraint scope_setting_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id)
);

create table scope_material_off (
    owner_id    uuid not null,
    campaign_id uuid not null,
    material_id uuid not null,
    constraint scope_material_off_pkey primary key (owner_id, campaign_id, material_id),
    constraint scope_material_off_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id)
);

create index scope_material_off_owner_material on scope_material_off (owner_id, material_id);

alter table turn
    add column other_tier boolean not null default false;

create table turn_material_off (
    owner_id    uuid not null,
    turn_id     uuid not null,
    material_id uuid not null,
    constraint turn_material_off_pkey primary key (owner_id, turn_id, material_id),
    constraint turn_material_off_turn_fkey foreign key (owner_id, turn_id) references turn (owner_id, id)
);
