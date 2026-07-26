CREATE TABLE advisor_action_followups (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  period VARCHAR(7) NOT NULL,
  source VARCHAR(16) NOT NULL,
  recommendation_snapshot_id BIGINT REFERENCES advisor_recommendations(id) ON DELETE SET NULL,
  action_index INTEGER,
  action_key VARCHAR(64) NOT NULL,
  horizon VARCHAR(32),
  priority VARCHAR(32),
  title VARCHAR(255) NOT NULL,
  detail TEXT,
  kpi VARCHAR(255),
  status VARCHAR(16) NOT NULL,
  carried_over BOOLEAN NOT NULL DEFAULT FALSE,
  origin_follow_up_id BIGINT REFERENCES advisor_action_followups(id) ON DELETE SET NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_advisor_action_followups_company_period_source_key
  ON advisor_action_followups(company_id, period, source, action_key);

CREATE INDEX idx_advisor_action_followups_company_period
  ON advisor_action_followups(company_id, period);

CREATE INDEX idx_advisor_action_followups_company_source_period
  ON advisor_action_followups(company_id, source, period);
