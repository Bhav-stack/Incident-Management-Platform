-- Replay harness / shadow mode scores. `correct` is NULL for live shadow
-- rows (no ground truth); precision/recall come from rows with a verdict.
create table evaluations (
    id              bigserial primary key,
    external_id     uuid not null unique,
    service         varchar(120) not null,
    signal          varchar(80)  not null,
    expected_action varchar(80),
    proposed_action varchar(80),
    correct         boolean,
    confidence      double precision not null default 0,
    shadow          boolean not null default false,
    created_at      timestamptz not null
);

create index idx_evaluations_created_at on evaluations (created_at desc);