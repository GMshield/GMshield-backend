-- Story 5.6 (part A): the GM shares an artifact with other campaigns of its system (FR-38).
-- artifact_share: one row per pair of an artifact (artifact_id, a record of its own campaign) and a campaign of the same
-- owner and the same system it is shared with (campaign_id, never the artifact's own campaign). It stays one artifact:
-- nothing is copied, no index job is created and no index point is written. When campaign B's system tier is on, the
-- live artifacts shared into B are sources of its system tier; whether a row applies (a live artifact, a readable
-- campaign) is worked out when it is read, never stored. The service checks the target (the owner's other campaign of
-- the artifact's system); the table checks only the owner and the pair.
-- Both keys include the owner, as every library key does. The artifact's cascades, as record_relation's (V50): the
-- artifact's row removed (its deletion's pass, its campaign's or its system's deletion) takes its shares with it. The
-- campaign's has no cascade, as every campaign key: a campaign's deletion removes the shares into it explicitly, in the
-- artifacts' part of that deletion, before the campaign row goes.
-- The index on (owner, campaign) serves the reads of the artifacts shared into a campaign (its scope, a read there).
-- Rolling back past V52: the previous backend release neither reads nor writes this table, so a shared artifact simply
-- stops being a source of the other campaigns. Before running that release, drop the table, because otherwise deleting
-- a campaign that has a share into it fails on artifact_share_campaign_fkey:
--   drop table artifact_share;
-- Structure only: no trigger, procedure or row-level security.
create table artifact_share (
    artifact_id uuid        not null,
    campaign_id uuid        not null,
    owner_id    uuid        not null,
    created_at  timestamptz not null,
    constraint artifact_share_pkey primary key (artifact_id, campaign_id),
    constraint artifact_share_artifact_fkey foreign key (owner_id, artifact_id) references record (owner_id, id)
        on delete cascade,
    constraint artifact_share_campaign_fkey foreign key (owner_id, campaign_id) references campaign (owner_id, id)
);

create index artifact_share_owner_campaign on artifact_share (owner_id, campaign_id);
