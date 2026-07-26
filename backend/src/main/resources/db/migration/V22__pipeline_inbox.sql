CREATE TABLE ingestion_inbox_files (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  source_type VARCHAR(32) NOT NULL,
  detected_kind VARCHAR(32) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  inbox_path TEXT NOT NULL,
  archived_path TEXT,
  period VARCHAR(16),
  status VARCHAR(32) NOT NULL,
  message TEXT,
  import_job_id BIGINT REFERENCES imports(id) ON DELETE SET NULL,
  detected_at TIMESTAMP NOT NULL DEFAULT NOW(),
  processed_at TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ingestion_inbox_files_company_detected_at
  ON ingestion_inbox_files(company_id, detected_at DESC);

CREATE INDEX idx_ingestion_inbox_files_company_status
  ON ingestion_inbox_files(company_id, status);
