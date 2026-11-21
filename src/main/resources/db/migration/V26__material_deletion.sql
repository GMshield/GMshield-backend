-- Story 3.4 part B (AD-22): deleting a material, and removing a superseded version of one, are the recorded, resumable
-- process of Story 3.1 part B (V22), of kinds 'material' (target: the material's id) and 'version' (target: the
-- version's id). No material or version column changes: the GM's delete discards the versions that go, in the same
-- transaction as the deletion row, so they leave every list and scope at once; the removal pass then deletes their
-- rows, files and index points, and the material's own row for kind 'material'.
-- deletion.kind now allows 'material' and 'version' beside 'system' and 'campaign'.
-- Additive: the previous release neither reads nor writes these kinds. After a rollback, a pending 'material' or
-- 'version' row is taken by that release's sweep as a system it cannot find and marked done; the versions it discarded
-- stay discarded, and for 'material' the material row may be left with no version. After rolling forward, the targets
-- with something left are those of
--   select kind, target_id from deletion where done_at is not null and ((kind = 'material' and target_id in
--   (select id from material)) or (kind = 'version' and target_id in (select id from material_version)));
-- reopening each row (done_at = null) lets the next pass finish it (README.md, "Delete a material").
-- Structure only: no trigger, procedure or row-level security.
alter table deletion
    drop constraint deletion_kind_check,
    add constraint deletion_kind_check check (kind in ('system', 'campaign', 'material', 'version'));
