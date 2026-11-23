-- Story 3.2 part B (AD-22): deleting a campaign is the recorded, resumable process of Story 3.1 part B (V22), of kind
-- 'campaign'.
-- campaign.state is 'ready', or 'deleting' from the commit of the GM's delete until the campaign's row goes: a deleting
-- campaign is "not found" to every read and write (lists, the current campaign, its conversation, questions, uploads).
-- When a campaign becomes 'deleting', its name_fingerprint is replaced by the campaign's own id (16 bytes, never a
-- 32-byte keyed fingerprint), so the GM can at once create a campaign with the same name in the same system.
-- A system's deletion still takes every campaign of the system, deleting ones included.
-- deletion.kind now allows 'campaign' (target: the campaign's id) beside 'system'.
-- Additive: the previous release neither reads nor writes campaign.state (its inserts take the default). After a
-- rollback a deleting campaign shows again, with whatever its removal has not removed yet; that release's sweep takes
-- a pending 'campaign' row for a system it cannot find and marks it done (README.md, "Delete a campaign").
-- Structure only: no trigger, procedure or row-level security.
alter table campaign
    add column state text not null default 'ready',
    add constraint campaign_state_check check (state in ('ready', 'deleting'));

alter table deletion
    drop constraint deletion_kind_check,
    add constraint deletion_kind_check check (kind in ('system', 'campaign'));
