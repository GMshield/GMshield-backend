-- Story 2.6 (AD-25, AD-32): the GM's stop, and a usage that is an estimate. turn.stopped_at is when the GM stopped the
-- turn's answer: set once, in the transaction that appends the turn's final done event (marked stopped); null for a
-- turn that was never stopped, or that had ended before the stop. model_call_usage.estimated is true when the worker
-- had no usage from the provider for a call it stopped early, and reported an estimate instead (code points / 4,
-- rounded up). Both are additive: the previous release, which neither reads nor writes them, keeps working on this
-- schema (a usage row it writes is not an estimate). Structure only: no trigger, procedure or row-level security.
alter table turn
    add column stopped_at timestamptz;

alter table model_call_usage
    add column estimated boolean not null default false;
