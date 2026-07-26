create table if not exists period_workflows (
    id bigserial primary key,
    company_id bigint not null references companies(id) on delete cascade,
    period varchar(7) not null,
    status varchar(32) not null,
    priority integer not null default 0,
    source_import_id bigint references imports(id) on delete set null,
    report_id bigint references reports(id) on delete set null,
    recommendation_snapshot_id bigint references advisor_recommendations(id) on delete set null,
    owner_user_id bigint references users(id) on delete set null,
    started_at timestamp with time zone not null,
    updated_at timestamp with time zone,
    reviewed_at timestamp with time zone,
    closed_at timestamp with time zone,
    blocking_code varchar(64),
    blocking_reason text,
    exception_count integer not null default 0,
    notes text,
    constraint uq_period_workflows_company_period unique (company_id, period)
);

create index if not exists idx_period_workflows_status_priority
    on period_workflows(status, priority desc, updated_at asc, id asc);

create index if not exists idx_period_workflows_company_period_status
    on period_workflows(company_id, period, status);
