-- Story 7.1: a campaign stores an optional party size and party level, and a question freezes them on its turn.
-- campaign.party_size and campaign.party_level are each one vault ciphertext of the value's decimal text, bound to the
-- entity "campaign", the field "party_size" or "party_level", the row and the owner; null while the GM has not set it.
-- turn.party_size and turn.party_level freeze the campaign's values when the turn is asked, as V17 and V27 froze the
-- scope switches, so a change made while a turn is answered applies from the next question; each is one vault
-- ciphertext bound to the entity "turn", the same field, the turn and the owner. A turn from before this release reads
-- as not set (null).
-- Additive: the previous release ignores these four columns and never writes them, so it runs on this schema
-- unchanged, and the party values stored before a rollback come back unchanged when this release is deployed again.
-- Structure only: no trigger, procedure or row-level security.
alter table campaign
    add column party_size  bytea,
    add column party_level bytea;

alter table turn
    add column party_size  bytea,
    add column party_level bytea;
