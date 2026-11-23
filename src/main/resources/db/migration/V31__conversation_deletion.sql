-- Story 3.10 part B (AD-22): deleting a conversation is the recorded, resumable process of Story 3.1 part B (V22), of
-- kind 'conversation' (target: the conversation's id).
-- conversation.state is 'ready', or 'deleting' from the commit of the GM's delete until the conversation's row goes: a
-- deleting conversation is "not found" to every read and write (its campaign's lists and counts, the conversation, its
-- turns, a question naming it, the campaign's latest questions conversation, and its turns' events, stop and reference
-- pages). A campaign's or a system's deletion still takes every conversation of its campaigns, deleting ones included.
-- deletion.kind now allows 'conversation' beside 'system', 'campaign', 'material' and 'version'.
-- Additive: the previous release neither reads nor writes conversation.state (its inserts take the default). After a
-- rollback a deleting conversation shows again, with whatever its removal has not removed yet; that release's sweep
-- takes a pending 'conversation' row for a system it cannot find and marks it done. Before rolling forward again, set
-- state = 'ready' on every deleting conversation with no pending 'conversation' deletion row, and drop the done rows
-- whose conversation still exists (README.md, "Delete a conversation", gives the statements), or it stays deleting
-- forever; the GM can then delete it again.
-- Structure only: no trigger, procedure or row-level security.
alter table conversation
    add column state text not null default 'ready',
    add constraint conversation_state_check check (state in ('ready', 'deleting'));

alter table deletion
    drop constraint deletion_kind_check,
    add constraint deletion_kind_check check (kind in ('system', 'campaign', 'material', 'version', 'conversation'));
