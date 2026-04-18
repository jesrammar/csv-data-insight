create table if not exists user_tokens (
  id bigserial primary key,
  user_id bigint not null references users(id) on delete cascade,
  purpose varchar(40) not null,
  token_hash varchar(64) not null unique,
  created_by_user_id bigint references users(id),
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  used_at timestamp with time zone
);

create index if not exists idx_user_tokens_user_purpose_active
  on user_tokens(user_id, purpose)
  ;

