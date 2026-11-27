-- Story 5.3 (part A): the GM asks the agent for a draft in the questions area.
-- turn.kind is what the GM asked for: 'question' (answered by a question.answer job, the default) or 'draft' (written
-- by a content.draft job from the same sources). A draft stays a turn, so bookmarks, dismissal, the history sent with a
-- later question and deletion treat it as an answer; the stored kind is what tells a draft from an answer when the
-- conversation is read again. Nothing is written to the library.
-- Additive: the previous release neither reads nor writes turn.kind (its inserts get the default 'question').
-- Rolling back past V47 is not safe while a draft is being written: the previous release has no content.draft handler,
-- so its claims fail such a job as an unknown kind. Before rolling back, cancel every unended job of kind
-- 'content.draft' (state waiting, offered or running); the draft turns then read as answers, and
--   alter table turn drop column kind;
-- removes the column.
-- Structure only: no trigger, procedure or row-level security.
alter table turn
    add column kind varchar(16) not null default 'question',
    add constraint turn_kind_check check (kind in ('question', 'draft'));
