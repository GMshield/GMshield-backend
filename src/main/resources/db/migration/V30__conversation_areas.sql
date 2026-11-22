-- Story 3.10 part A (AD-11): a campaign has many conversations, each in one area of the sidebar.
-- area is questions (Consultas) or rules (Criação de Regras); every existing conversation is a questions one.
-- name is the GM's name for the conversation, stored only encrypted through the vault, bound to the entity
-- "conversation", the field "name", the row and the owner; null until the GM renames it (every view then shows the
-- first question's first words). version starts at 0 and rises by one with each rename (Hibernate @Version): a rename
-- that names an earlier one is refused, and nothing changes. last_asked_at is when the conversation's newest question
-- was asked (a list's order, most recent first); asking never raises the version. An existing conversation takes its
-- newest turn's time, else its own.
-- A campaign may now have many conversations: the unique key on (owner, campaign) goes, and an index serves a list of
-- one area of one campaign by its last question. A conversation is still made by its first question only, so none is
-- empty.
-- Additive for the previous release, which reads none of these columns: its inserts take the defaults (questions, no
-- name, version 0, now), and it continues the oldest conversation of a campaign whatever its area. Structure and one
-- backfill only: no trigger, procedure or row-level security.
alter table conversation
    add column area          varchar(16) not null default 'questions',
    add column name          bytea,
    add column version       bigint      not null default 0,
    add column last_asked_at timestamptz,
    add constraint conversation_area_check check (area in ('questions', 'rules'));

update conversation c
   set last_asked_at = coalesce((select max(t.created_at) from turn t
                                  where t.owner_id = c.owner_id and t.conversation_id = c.id), c.created_at);

alter table conversation
    alter column last_asked_at set not null,
    alter column last_asked_at set default now(),
    drop constraint conversation_owner_campaign_key;

create index conversation_owner_campaign_area on conversation (owner_id, campaign_id, area, last_asked_at);
