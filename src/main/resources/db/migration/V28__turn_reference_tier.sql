-- Story 3.7 (AD-9, AD-11): a source reference keeps the tier its source had in the turn's frozen scope when the answer
-- was written (campaign, system or other), so it still shows that tier after the source leaves the scope. The tier is
-- the backend's own label (the scope resolver's); it is stored in the clear, like version_id and page, since it names
-- no library content. A row written before this release has no tier (null) and shows none: there is no backfill.
-- Additive: the previous release neither reads nor writes this column, and after a rollback the stored tiers are
-- ignored. The tier never reaches the index (AD-10). Structure only: no trigger, procedure or row-level security.
alter table turn_reference
    add column tier varchar(16)
        constraint turn_reference_tier_check check (tier in ('campaign', 'system', 'other'));
