-- Story 6.2 (part B, AD-22): deleting a Shield becomes the recorded, resumable process of Story 3.1 part B (V22), of
-- kind 'shield' (target: the Shield's id), because a Shield now holds notes (V54) that go with it as artifacts, with
-- their index points.
-- shield.deleting_at: set by the GM's delete, in the transaction that records the deletion (under the campaign's row
-- lock); from that commit the Shield is "deleting": every Shield read and count leaves it out, no note can be created
-- in it or moved into it, and its name is free in its campaign at once (a named Shield's name_fingerprint becomes its
-- own id, 16 bytes, never a keyed fingerprint's 32, as a deleting campaign's does; an unnamed one keeps both null).
-- The same transaction marks its live notes deleting (record.deleted_at, V46). The pass then removes every note the
-- Shield holds, as an artifact's deletion removes it (one 'index.artifact_removal' job each), and deletes the Shield's
-- row. A campaign's or a system's deletion still removes its deleting Shields with the campaign.
-- deletion.kind now allows 'shield' beside 'system', 'campaign', 'material', 'version', 'conversation' and 'artifact'.
-- Rolling back past V55 is not safe while a Shield deletion is pending: the previous backend release reads a deleting
-- Shield as a live one and its sweep cannot run a 'shield' row. Before rolling back, let every pending 'shield'
-- deletion finish (no deletion row of kind 'shield' with done_at null, and no shield row with deleting_at set), then
-- delete the 'shield' deletion rows, and restore the previous structure:
--   alter table deletion drop constraint deletion_kind_check, add constraint deletion_kind_check
--       check (kind in ('system', 'campaign', 'material', 'version', 'conversation', 'artifact'));
--   alter table shield drop column deleting_at;
-- Structure only: no trigger, procedure or row-level security.
alter table shield add column deleting_at timestamptz;

alter table deletion
    drop constraint deletion_kind_check,
    add constraint deletion_kind_check check (
        kind in ('system', 'campaign', 'material', 'version', 'conversation', 'artifact', 'shield'));
