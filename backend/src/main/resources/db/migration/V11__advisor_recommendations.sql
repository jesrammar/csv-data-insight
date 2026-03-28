CREATE TABLE advisor_recommendations (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  period VARCHAR(7) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  source VARCHAR(16) NOT NULL,
  summary TEXT,
  actions_json TEXT NOT NULL
);

CREATE UNIQUE INDEX uq_advisor_recommendations_company_period_source
  ON advisor_recommendations(company_id, period, source);

CREATE INDEX idx_advisor_recommendations_company_period
  ON advisor_recommendations(company_id, period);

