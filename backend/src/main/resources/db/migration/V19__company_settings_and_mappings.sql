create table if not exists company_settings (
  company_id bigint primary key references companies(id) on delete cascade,
  working_period varchar(7),
  auto_monthly_report boolean not null default true,
  created_at timestamp not null default now(),
  updated_at timestamp not null default now()
);

create table if not exists company_saved_mappings (
  id bigserial primary key,
  company_id bigint not null references companies(id) on delete cascade,
  mapping_key varchar(64) not null,
  payload_json text not null,
  created_at timestamp not null default now(),
  updated_at timestamp not null default now(),
  constraint uq_company_saved_mappings_company_key unique (company_id, mapping_key)
);

