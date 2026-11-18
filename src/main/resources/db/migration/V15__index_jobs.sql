-- Story 2.1 (AD-10, AD-13): the index jobs of a material version. The backend writes them from this release on: no
-- earlier release holds an index row (Dalton, 2026-11-18, a one-time exception to "understood in one release, written
-- in the next"). A backend rolled back below this release must first cancel every unended job of kind 'index.change'
-- or 'index.removal', whose handlers it does not have (README.md, "Index an accepted material", gives the statement).
-- material_version.index_recipe is the worker's index recipe that every index.change job of the version's current
-- set reported, set when the last of them completes; null until then, and again when a new set starts.
-- index_change is the input of one job of kind 'index.change' (job.id): a run of at most 200 of the version's passages
-- by ordinal, first and last inclusive, and the recipe its result reported (null until it completes). index_removal is
-- the input of one job of kind 'index.removal': the version whose points go. Both are library tables: each row carries
-- its owner, filled and filtered by Hibernate @TenantId, and every key includes it, as in V9. index_removal has no
-- reference to the version, so it outlives a version that is deleted (Story 3.4). Neither holds content, a name or a
-- key: the worker gets the passages through the job's input `passages`, and the owner's token key with the claim.
-- Structure only: no trigger, procedure or row-level security.
alter table material_version
    add column index_recipe varchar(32);

create table index_change (
    job_id        uuid        primary key,
    owner_id      uuid        not null,
    version_id    uuid        not null,
    first_ordinal integer     not null check (first_ordinal >= 0),
    last_ordinal  integer     not null,
    recipe        varchar(32),
    constraint index_change_owner_job_key unique (owner_id, job_id),
    constraint index_change_ordinals_check check (last_ordinal >= first_ordinal),
    constraint index_change_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id)
);

create index index_change_owner_version on index_change (owner_id, version_id);

create table index_removal (
    job_id     uuid primary key,
    owner_id   uuid not null,
    version_id uuid not null,
    constraint index_removal_owner_job_key unique (owner_id, job_id)
);

-- What the reconcile looks at across owners: ready versions, by their recipe.
create index material_version_ready_recipe on material_version (owner_id, index_recipe) where state = 'ready';
