-- Story 5.5 (part A): an artifact overrides a system record in its own campaign (FR-20).
-- record_relation: a link from one record of the owner (from_record_id, here always an artifact) to another
-- (to_record_id, here always an extracted record of the campaign's system tier), of one kind. Part A has one kind,
-- 'overrides'; part B adds its own to the check. An artifact overrides at most one record (unique from_record_id, kind).
-- Whether a mark applies is worked out when it is read, never stored: in campaign C a record is overridden only by a
-- live artifact of C (a deleting one has no effect), while the record is live and in the system tier of C's scope;
-- otherwise the mark is shown as removed and has no effect. One record has at most one override per campaign: the
-- service refuses a second (409 artifact.override_taken), under a lock on the record's row.
-- The relation is not versioned: an artifact's edit or restore keeps it. A reprocess's acceptance moves it to the
-- matching record of the new version, where Story 4.5 carries corrections; a replacement upload carries nothing.
-- Both keys include the owner, as every library key does, and cascade: an artifact's or a record's row removed (the
-- artifact deletion's pass, a version's removal, a campaign's or a system's deletion) takes its links with it.
-- Rolling back past V50 is safe: the previous backend release neither reads nor writes this table, so an override
-- simply stops applying. To remove it:
--   drop table record_relation;
-- Structure only: no trigger, procedure or row-level security.
create table record_relation (
    id             uuid        primary key,
    owner_id       uuid        not null,
    from_record_id uuid        not null,
    to_record_id   uuid        not null,
    kind           varchar(16) not null constraint record_relation_kind_check check (kind in ('overrides')),
    created_at     timestamptz not null,
    constraint record_relation_owner_id_key unique (owner_id, id),
    constraint record_relation_from_kind_key unique (from_record_id, kind),
    constraint record_relation_self_check check (from_record_id <> to_record_id),
    constraint record_relation_from_fkey foreign key (owner_id, from_record_id) references record (owner_id, id)
        on delete cascade,
    constraint record_relation_to_fkey foreign key (owner_id, to_record_id) references record (owner_id, id)
        on delete cascade
);

create index record_relation_owner_to on record_relation (owner_id, to_record_id, kind);
