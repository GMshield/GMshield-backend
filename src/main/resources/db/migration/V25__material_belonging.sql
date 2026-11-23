-- Story 3.3: where a material belongs can change. A material belongs to one campaign (material.campaign_id) or to one
-- or more game systems (material_system rows, at most 100); the application enforces that rule, since a CHECK cannot
-- see both tables. version starts at 0 and rises by one with each change of where the material belongs (Hibernate
-- @Version): a change that names an earlier one is refused, and nothing changes. material.campaign_id becomes
-- writable by the application (no schema change: its owner-scoped FK to campaign already holds). Additive: the
-- previous release, which neither reads nor writes version, keeps working on this schema (its inserts take the
-- default). Structure only: no trigger, procedure or row-level security.
alter table material
    add column version bigint not null default 0;
