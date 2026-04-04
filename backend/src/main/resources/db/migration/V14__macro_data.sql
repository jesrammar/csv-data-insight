create table if not exists macro_series (
  id bigserial primary key,
  provider varchar(32) not null,
  code varchar(128) not null,
  name varchar(256) not null,
  unit varchar(64),
  frequency varchar(16),
  last_fetched_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (provider, code)
);

create table if not exists macro_observation (
  id bigserial primary key,
  series_id bigint not null references macro_series(id) on delete cascade,
  period varchar(16) not null,
  value numeric,
  created_at timestamptz not null default now(),
  unique (series_id, period)
);

create index if not exists idx_macro_obs_series_period on macro_observation(series_id, period);
