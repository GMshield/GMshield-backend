-- Story 5.2 (part B, AD-22): the GM deletes an artifact. It is the recorded, resumable process of Story 3.1 part B
-- (V22), of kind 'artifact' (target: the artifact's id, a row of record with a campaign).
-- The GM's transaction records the deletion and sets record.deleted_at (V42) on the artifact's row: from that commit
-- the artifact is "deleting", and every artifact read leaves it out (its page, its versions, an edit or a restore, the
-- campaign's list and type counts, "Personagens", the campaign tier of a question's scope, the backfill and retries of
-- its index, the type-in-use guard and the counts of the campaign and system deletion dialogs). The pass then removes
-- its artifact_index rows, its versions and its row, and creates one 'index.artifact_removal' job for its points. A
-- campaign's or a system's deletion still removes its deleting artifacts. An artifact row is never a tombstone after
-- the pass: point_removed_at stays null on it.
-- deletion.kind now allows 'artifact' beside 'system', 'campaign', 'material', 'version' and 'conversation'.
-- Rolling back past V46 is not safe while an artifact deletion is pending: the previous backend release reads a
-- deleting artifact as a live one (listed, counted, searched, indexed again) and its sweep cannot run an 'artifact'
-- row. Before rolling back, let every pending 'artifact' deletion finish (no deletion row of kind 'artifact' with
-- done_at null, and no record row with a campaign and deleted_at set), then delete the 'artifact' deletion rows, and
-- restore the previous check:
--   alter table deletion drop constraint deletion_kind_check, add constraint deletion_kind_check
--       check (kind in ('system', 'campaign', 'material', 'version', 'conversation'));
-- Structure only: no trigger, procedure or row-level security.
alter table deletion
    drop constraint deletion_kind_check,
    add constraint deletion_kind_check check (
        kind in ('system', 'campaign', 'material', 'version', 'conversation', 'artifact'));
