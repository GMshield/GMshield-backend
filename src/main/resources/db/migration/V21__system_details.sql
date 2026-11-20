-- Story 3.1: a system's optional edition and description, and the version an edit names. The edition and the
-- description are stored only encrypted through the vault, like the name, each bound to the entity "system", its own
-- field ("edition", "description"), the row and the owner; null when the GM gave none. version starts at 0 and rises
-- by one with each edit (Hibernate @Version): an edit that names an earlier one is refused, and nothing changes.
-- From this release name_fingerprint covers the name and the edition (two editions are two systems); a row without an
-- edition keeps the fingerprint of its name alone, so no row is rewritten. Additive: the previous release, which
-- neither reads nor writes these columns, keeps working on this schema (its inserts take the defaults). Structure
-- only: no trigger, procedure or row-level security.
alter table system
    add column edition     bytea,
    add column description bytea,
    add column version     bigint not null default 0;
