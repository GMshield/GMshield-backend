-- Story 5.4 (part A): the GM saves a finished draft through the save confirmation.
-- A draft turn (turn.kind 'draft') that is done, neither stopped nor silent, is offered for saving. "Salvar" creates an
-- artifact of the conversation's campaign (record, record_version and its index job, as Story 5.1 writes them) and marks
-- the turn saved, in one transaction; "Agora não" declines it, and "Salvar como artefato" opens it again.
-- record.conversation_id: the conversation an artifact was saved from; null for an artifact written by hand. The
-- reference includes the owner, as every library key does. Deleting the conversation keeps the artifact and sets only
-- this column to null (ON DELETE SET NULL on the column alone, PostgreSQL 15 or later), so an artifact never points at a
-- conversation that is gone. Set only on an artifact (a row with a campaign).
-- turn.saved_record_id: the artifact the draft was saved as. One draft makes at most one artifact: the save is one
-- conditional update that requires the column to be null (or the artifact it named to be deleted). Deleting the artifact
-- (Story 5.2 part B's pass removes its row) sets only this column to null.
-- turn.save_declined_at: when the card was closed: by "Agora não", and by a save too, so a turn whose saved artifact was
-- deleted (saved_record_id now null) reads "declined" rather than offered again. "Salvar como artefato" clears it (and a
-- saved_record_id whose artifact is deleted or being deleted). The turn's state is worked out when it is read: saved
-- while saved_record_id names a live artifact, declined while either column is set otherwise, offered when neither is.
-- Both columns are written by bulk updates only, never by a turn's flush.
-- artifact_reference: the source references a saved draft's turn had, copied by value when it was saved, keyed by the
-- artifact (record_id) and the reference's number. Its columns are turn_reference's (V18, V28, V38, V44): a passage's,
-- a record's or an artifact's reference, under the same check. The material's name, the section path, and a record's
-- or an artifact's name and fields are stored only encrypted through the vault, bound to the entity
-- "artifact_reference", the field (with the number, e.g. "material_name:2"), the artifact's id and the owner: they are
-- sealed again under the artifact, never copied as the turn's ciphertexts. version_id, material_id and artifact_id are
-- kept by value with no reference, so a source removed later still shows, marked removed when read. An edit or a restore
-- of the artifact keeps them. They are deleted with the artifact (ON DELETE CASCADE, and the artifact deletion's pass
-- deletes them before the versions).
-- Rolling back past V49 is safe: the previous backend release neither reads nor writes these columns or this table (its
-- inserts leave the columns null), so saved artifacts read as written by hand, and saved drafts are offered again by
-- nothing (that release has no save card). To remove them:
--   drop table artifact_reference;
--   alter table turn drop constraint turn_saved_record_fkey, drop column saved_record_id, drop column save_declined_at;
--   alter table record drop constraint record_conversation_fkey, drop constraint record_conversation_check,
--       drop column conversation_id;
-- Structure only: no trigger, procedure or row-level security.
alter table record add column conversation_id uuid;
alter table record add constraint record_conversation_fkey foreign key (owner_id, conversation_id)
    references conversation (owner_id, id) on delete set null (conversation_id);
alter table record add constraint record_conversation_check check (conversation_id is null or campaign_id is not null);
create index record_owner_conversation on record (owner_id, conversation_id) where conversation_id is not null;

alter table turn
    add column saved_record_id uuid,
    add column save_declined_at timestamptz,
    add constraint turn_saved_record_fkey foreign key (owner_id, saved_record_id)
        references record (owner_id, id) on delete set null (saved_record_id);
create index turn_owner_saved_record on turn (owner_id, saved_record_id) where saved_record_id is not null;

create table artifact_reference (
    record_id         uuid        not null,
    owner_id          uuid        not null,
    number            integer     not null check (number >= 1),
    version_id        uuid,
    material_id       uuid,
    page              integer     check (page >= 1),
    passage_ids       uuid[]      check (cardinality(passage_ids) >= 1),
    material_name     bytea,
    section_path      bytea,
    tier              varchar(16) constraint artifact_reference_tier_check check (tier in ('campaign', 'system', 'other')),
    cited_record_id   uuid,
    record_type       varchar(32) constraint artifact_reference_record_type_check check (
        record_type ~ '^[a-z][a-z0-9_]{0,31}$'),
    record_name       bytea,
    record_fields     bytea,
    artifact_id       uuid,
    artifact_version  integer     check (artifact_version >= 1),
    created_at        timestamptz not null,
    constraint artifact_reference_pkey primary key (record_id, number),
    constraint artifact_reference_record_fkey foreign key (owner_id, record_id) references record (owner_id, id)
        on delete cascade,
    constraint artifact_reference_kind_check check (
        (artifact_id is null and artifact_version is null
            and version_id is not null and material_id is not null and page is not null and passage_ids is not null
            and material_name is not null and section_path is not null
            and ((cited_record_id is null and record_type is null and record_name is null and record_fields is null)
                or (cited_record_id is not null and record_type is not null and record_name is not null
                    and record_fields is not null)))
        or (artifact_id is not null and artifact_version is not null and cited_record_id is null
            and record_type is not null and record_name is not null and record_fields is not null
            and version_id is null and material_id is null and page is null and passage_ids is null
            and material_name is null and section_path is null))
);
