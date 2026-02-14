CREATE TABLE refresh_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP,
  replaced_by_hash VARCHAR(128)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);

CREATE TABLE revoked_tokens (
  jti VARCHAR(64) PRIMARY KEY,
  expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP NOT NULL
);
