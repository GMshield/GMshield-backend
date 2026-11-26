-- Story 5.1 (part B): artifacts answer questions. A saved artifact is indexed, joins the campaign tier of its campaign's
-- questions, and an answer that uses one cites it by value.
-- artifact_index is the input of one job of kind 'index.artifact' (job.id): the artifact version it indexes (its record
-- and number) and the index recipe its result reported (null until it completes, the version's recipe once it has). An
-- artifact's save writes its row and job in the same transaction; a worker's new recipe gives each version not indexed
-- under it a new row and job (the old row goes); a failed job's row moves to the job that retries it (retries counts
-- them, at most 3, as index_change's does). The reference names the record with the owner, as every library key does,
-- and the version's number with its record, so a row never outlives its version: a campaign's deletion removes the
-- rows first, then the versions and the identities.
-- artifact_index_removal is the input of one job of kind 'index.artifact_removal': the artifact whose points go. Like
-- index_removal (V15) it has no reference to the artifact, so it outlives the artifact it names.
-- turn_reference: an answer may cite an artifact. Its reference is stored by value as well: the artifact's id and
-- version, and its record type, name and fields (record_type, record_name and record_fields, encrypted as a record's),
-- never its content. It names no material: its version, material, page, passages, material name and section path are
-- all null, and record_id too (artifact_id names it). A passage's or a record's reference keeps every one of those, as
-- before; the check below replaces V38's, under the same name, and holds both.
-- Rolling back past V44 is safe while no artifact has been cited: the previous backend release neither reads nor writes
-- artifact_index or artifact_index_removal, and it reads only the turn_reference rows it wrote. An artifact's reference
-- has none of the material columns that release reads, so once one is stored, its turn's references fail to load there:
-- delete those rows first (delete from turn_reference where artifact_id is not null). And end every 'index.artifact' or
-- 'index.artifact_removal' job still waiting, offered or running, whose handlers that release does not have, as
-- Jobs.cancel ends a job, with the backend stopped:
--   update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null,
--                  outcome_code = null, updated_at = now()
--    where kind in ('index.artifact', 'index.artifact_removal') and state in ('waiting', 'offered', 'running');
-- The artifacts' points stay in the index, under their own sources, which that release never puts in a scope.
-- Structure only: no trigger, procedure or row-level security.
create table artifact_index (
    job_id     uuid        primary key,
    owner_id   uuid        not null,
    record_id  uuid        not null,
    number     integer     not null check (number >= 1),
    recipe     varchar(32),
    retries    smallint    not null default 0 check (retries between 0 and 3),
    created_at timestamptz not null,
    constraint artifact_index_owner_job_key unique (owner_id, job_id),
    constraint artifact_index_record_fkey foreign key (owner_id, record_id) references record (owner_id, id),
    constraint artifact_index_version_fkey foreign key (record_id, number) references record_version (record_id, number)
);

create index artifact_index_owner_record on artifact_index (owner_id, record_id, number);

create table artifact_index_removal (
    job_id      uuid primary key,
    owner_id    uuid not null,
    artifact_id uuid not null,
    constraint artifact_index_removal_owner_job_key unique (owner_id, job_id)
);

alter table turn_reference alter column version_id drop not null;
alter table turn_reference alter column material_id drop not null;
alter table turn_reference alter column page drop not null;
alter table turn_reference alter column passage_ids drop not null;
alter table turn_reference alter column material_name drop not null;
alter table turn_reference alter column section_path drop not null;
alter table turn_reference add column artifact_id uuid;
alter table turn_reference add column artifact_version integer check (artifact_version >= 1);
alter table turn_reference drop constraint turn_reference_record_check;
alter table turn_reference add constraint turn_reference_record_check check (
    (artifact_id is null and artifact_version is null
        and version_id is not null and material_id is not null and page is not null and passage_ids is not null
        and material_name is not null and section_path is not null
        and ((record_id is null and record_type is null and record_name is null and record_fields is null)
            or (record_id is not null and record_type is not null and record_name is not null
                and record_fields is not null)))
    or (artifact_id is not null and artifact_version is not null and record_id is null
        and record_type is not null and record_name is not null and record_fields is not null
        and version_id is null and material_id is null and page is null and passage_ids is null
        and material_name is null and section_path is null));
