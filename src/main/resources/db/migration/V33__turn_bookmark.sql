-- Story 3.12 (FR-34, FR-36): the GM bookmarks an answer and finds it again.
-- turn.bookmarked_at is when the GM saved the turn's answer; null while it is not saved. Only a turn whose last stored
-- event is a done event (not stopped, not failed, not still running, not silent) is saved, and the GM's unsave sets it
-- back to null. The saved answers list reads the owner's saved turns newest first, so an index on
-- (owner_id, bookmarked_at) covers the saved rows only. A bookmark gives an answer no standing as a source: scope
-- resolution, retrieval and the history sent with a later question never read the column. A dismissed turn keeps its
-- bookmarked_at (an undo of the dismissal lists it again), and deleting the turn by any route (its conversation, its
-- campaign, its system) removes the bookmark with the row.
-- Only two bulk updates write the column (set when null, and clear), so a full-row write of the turn (a racing stop)
-- never undoes a bookmark; neither changes the conversation's version or last_asked_at.
-- Additive: the previous release neither reads nor writes turn.bookmarked_at (its inserts leave it null). After a
-- rollback the bookmarks are kept but not shown; rolling forward shows them again.
-- Structure only: no trigger, procedure or row-level security.
alter table turn
    add column bookmarked_at timestamptz;

create index turn_owner_bookmarked on turn (owner_id, bookmarked_at) where bookmarked_at is not null;
