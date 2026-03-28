CREATE TABLE automation_jobs (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT REFERENCES companies(id) ON DELETE CASCADE,
  type VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 5,
  run_after TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  payload_json TEXT,
  last_error TEXT
);

CREATE INDEX idx_automation_jobs_due ON automation_jobs(status, run_after);
CREATE INDEX idx_automation_jobs_company ON automation_jobs(company_id);

