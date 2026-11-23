-- Story 3.1 part B (AD-22): deleting a system is a recorded process that resumes after a restart.
-- system.state is 'ready', or 'deleting' from the commit of the GM's delete until the system's row goes: a deleting
-- system and its campaigns are "not found" to every read and write. When a system becomes 'deleting', its
-- name_fingerprint is replaced by the system's own id (16 bytes, never a 32-byte keyed fingerprint), so the GM can at
-- once create a system with the same name and edition.
-- deletion is the record of one such process: its owner, its kind ('system'), its target (the system's id), the time
-- its removal may start (not_before: when the claims of the jobs it cancelled have lapsed) and when it ended (done_at,
-- null until then). It is not a library table: it holds ids and times only, no content, and the removal sweep reads it
-- across owners (by the partial index) and then works as each owner. Written with the GM's delete in one transaction.
-- Additive: the previous release neither reads nor writes either; after a rollback a deleting system shows again,
-- with its campaigns, and whatever its removal already removed is gone (README.md, "Delete a system").
-- Structure only: no trigger, procedure or row-level security.
alter table system
    add column state text not null default 'ready',
    add constraint system_state_check check (state in ('ready', 'deleting'));

create table deletion (
    id         uuid        primary key,
    owner_id   uuid        not null,
    kind       varchar(16) not null check (kind in ('system')),
    target_id  uuid        not null,
    not_before timestamptz not null,
    created_at timestamptz not null,
    done_at    timestamptz,
    constraint deletion_owner_target_key unique (owner_id, kind, target_id)
);

-- What the removal sweep looks at: deletions not done, the earliest first.
create index deletion_pending on deletion (not_before, id) where done_at is null;
