CREATE TABLE universal_views (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  config_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_universal_views_company ON universal_views(company_id);

