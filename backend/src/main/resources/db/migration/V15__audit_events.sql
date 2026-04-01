CREATE TABLE audit_events (
  id BIGSERIAL PRIMARY KEY,
  at TIMESTAMP NOT NULL,
  user_id BIGINT,
  company_id BIGINT,
  action VARCHAR(64) NOT NULL,
  method VARCHAR(8),
  path TEXT,
  status INT,
  duration_ms BIGINT,
  ip VARCHAR(64),
  user_agent TEXT,
  resource_type VARCHAR(32),
  resource_id VARCHAR(64),
  meta_json TEXT
);

CREATE INDEX idx_audit_company_at ON audit_events(company_id, at DESC);
CREATE INDEX idx_audit_user_at ON audit_events(user_id, at DESC);

