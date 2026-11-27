-- Story 5.3 (part B): a finished draft says what it is.
-- Once a draft (turn.kind 'draft') has streamed, the worker proposes a name and a record type for it, which Story 5.4's
-- save confirmation shows. turn.proposal_name is that name, a vault ciphertext (binding turn/proposal_name); null when
-- the turn has no proposal (a question, a stopped or failed draft, or a draft finished by a worker that reports none).
-- turn.proposal_type is the proposed record type key, plain: null, or a key of the turn's campaign's system when the
-- proposal was stored (a key that was not one is stored as null). The type is set only with a name.
-- Both are written once, by one bulk update in the transaction that appends the draft's done event, so no reader sees a
-- done draft without its proposal. Nothing is written to the library.
-- Additive: the previous release neither reads nor writes either column (its inserts leave them null). After a rollback
-- the proposals are kept but not shown; rolling forward shows them again. To remove them,
--   alter table turn drop constraint turn_proposal_type_check, drop column proposal_type, drop column proposal_name;
-- Structure only: no trigger, procedure or row-level security.
alter table turn
    add column proposal_name bytea,
    add column proposal_type varchar(32),
    add constraint turn_proposal_type_check check (proposal_type is null or proposal_name is not null);
