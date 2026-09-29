create table projects (
    id            uuid primary key,
    name          text        not null,
    created_at    timestamptz not null default now(),
    input_path    text,
    feature_count bigint      not null default 0,
    bbox          jsonb,
    counts        jsonb,
    diagnostics   jsonb
);

create table features (
    id          bigserial primary key,
    project_id  uuid not null references projects (id) on delete cascade,
    object_id   text,
    object_type text,
    properties  jsonb,
    geometry    jsonb
);

create index features_project_type on features (project_id, object_type);

create table jobs (
    id          uuid primary key,
    project_id  uuid not null references projects (id) on delete cascade,
    mode        text        not null,
    settings    jsonb,
    status      text        not null,
    progress    int         not null default 0,
    log         jsonb,
    error       text,
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    result_path text,
    summary     jsonb
);

create index jobs_project on jobs (project_id, started_at desc);
