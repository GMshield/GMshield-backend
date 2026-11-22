-- Story 3.11 (FR-31): the GM dismisses an answer they do not want.
-- turn.dismissed_at is when the GM dismissed the turn (its question and its answer); null while it is shown. Only an
-- ended turn (stopped, or with a done or error event) is dismissed, and the GM's undo sets it back to null. From the
-- dismissal's commit the turn is never sent as history to a later question, never listed with its conversation's
-- turns, and left out of the conversation's question counts; its own routes (events, references, their pages) still
-- answer, the conversation's default name is still its first question's, and deleting the conversation removes it too.
-- Only two bulk updates write the column (set when null, and clear), so a full-row write of the turn (a racing stop)
-- never undoes a dismissal; neither changes the conversation's version or last_asked_at.
-- Additive: the previous release neither reads nor writes turn.dismissed_at (its inserts leave it null). After a
-- rollback every dismissed turn shows again and is sent as history again; rolling forward hides them again.
-- Structure only: no trigger, procedure or row-level security.
alter table turn
    add column dismissed_at timestamptz;
