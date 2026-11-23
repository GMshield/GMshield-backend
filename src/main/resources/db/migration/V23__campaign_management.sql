-- Story 3.2: the GM creates, renames, archives, restores and chooses campaigns.
-- campaign.version starts at 0 and rises by one with each rename, archive or restore (Hibernate @Version): an edit that
-- names an earlier one is refused, and nothing changes. Choosing a campaign never raises it.
-- archived_at is null while the campaign is in use; an archived campaign keeps every row, leaves the lists and the
-- current position, still opens by id, and comes back when it is restored (archived_at null again).
-- chosen_at is when the GM last made the campaign current (null: never, or not since it was archived). The current
-- campaign is the ready, not archived campaign with the latest chosen_at, else the oldest one: archiving or deleting the
-- chosen one falls back by itself, and a switch is one update.
-- name_fingerprint is the keyed fingerprint of the name's trimmed, NFC, lower-case form (as a system's), unique per
-- owner and system: two campaigns of one system cannot share a name. SQL cannot compute it, so existing rows keep null
-- (the unique key ignores nulls) until the backend fills them at start, per owner: campaign_fingerprint_backfill lists
-- the owners that still have such rows (ids only, no content; it is not a library table); the backend removes an owner
-- once its rows are filled. Until then a new or renamed campaign is also compared with the system's campaigns that
-- have no fingerprint yet.
-- Additive: the previous release neither reads nor writes these columns (its inserts take the defaults and a null
-- fingerprint); after a rollback archived campaigns show again in its single-campaign shell.
-- Structure only: no trigger, procedure or row-level security.
alter table campaign
    add column version          bigint not null default 0,
    add column archived_at      timestamptz,
    add column chosen_at        timestamptz,
    add column name_fingerprint bytea,
    add constraint campaign_owner_system_name_fingerprint_key unique (owner_id, system_id, name_fingerprint);

create table campaign_fingerprint_backfill (
    owner_id uuid primary key
);

insert into campaign_fingerprint_backfill (owner_id)
select distinct owner_id from campaign where name_fingerprint is null;
