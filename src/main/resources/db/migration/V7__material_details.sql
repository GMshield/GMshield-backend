-- Story 1.14 (AD-11, AD-19): what a GM says about a material when uploading it. All of it is optional, so the
-- previous backend release, which writes none of it, keeps working.
-- kind and language are short codes, each checked against every value it may take; a language the GM left to
-- detection is null, decided by a later read stage. Where the material belongs is either one campaign
-- (material.campaign_id) or game systems (material_system, one row each; one in this release, several from Epic 3),
-- always of the same owner: every key and reference includes the owner, as in V4. material_system is a library
-- table (owner column, Hibernate @TenantId, JPA only). Its material reference deletes with the material, so a
-- previous release that removes a refused upload's material (Story 1.13) is never stopped by a row it does not know.
-- Structure only: no trigger, procedure or row-level security.
alter table material
    add column kind        varchar(16)
                           check (kind in ('rulebook', 'supplement', 'homebrew', 'campaign_notes', 'other')),
    add column language    varchar(8)
                           check (language in ('pt-BR', 'en')),
    add column campaign_id uuid,
    add constraint material_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id);

create index material_owner_campaign on material (owner_id, campaign_id);

create table material_system (
    id          uuid        primary key,
    owner_id    uuid        not null,
    material_id uuid        not null,
    system_id   uuid        not null,
    created_at  timestamptz not null default now(),
    constraint material_system_owner_id_key unique (owner_id, id),
    constraint material_system_owner_material_system_key unique (owner_id, material_id, system_id),
    constraint material_system_material_fkey foreign key (owner_id, material_id) references material (owner_id, id)
        on delete cascade,
    constraint material_system_system_fkey foreign key (owner_id, system_id) references system (owner_id, id)
);

create index material_system_owner_system on material_system (owner_id, system_id);
