alter table period_workflows
    add column if not exists portfolio_import_id bigint references tribunal_imports(id) on delete set null;

create index if not exists idx_period_workflows_portfolio_import
    on period_workflows(portfolio_import_id);
