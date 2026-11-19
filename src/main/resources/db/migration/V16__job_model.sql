-- Story 2.10 (AD-18): the model of a job's attempt. A claim of a kind that calls a model records the provider, the
-- provider's model id and the tier it resolved, and each later claim overwrites them, so once the job ends the row
-- names the model that answered. All three are null for every other kind, and before the first claim. Nullable and
-- added without a default: the previous release, which neither reads nor writes them, keeps working on this schema.
-- Ids and words only, never content.
alter table job
    add column model_provider varchar(32),
    add column model_id       varchar(128),
    add column model_tier     varchar(16);
