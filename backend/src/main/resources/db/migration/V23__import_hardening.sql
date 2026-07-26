ALTER TABLE imports ADD COLUMN content_hash VARCHAR(64);
ALTER TABLE imports ADD COLUMN normalized_hash VARCHAR(64);
ALTER TABLE imports ADD COLUMN version_no INT NOT NULL DEFAULT 1;
ALTER TABLE imports ADD COLUMN supersedes_import_id BIGINT REFERENCES imports(id);
ALTER TABLE imports ADD COLUMN duplicate_of_import_id BIGINT REFERENCES imports(id);
ALTER TABLE imports ADD COLUMN blocking_code VARCHAR(64);
ALTER TABLE imports ADD COLUMN blocking_reason TEXT;
ALTER TABLE imports ADD COLUMN rows_received INT;
ALTER TABLE imports ADD COLUMN rows_valid INT;
ALTER TABLE imports ADD COLUMN applied_at TIMESTAMP;

UPDATE imports
SET version_no = COALESCE(version_no, 1)
WHERE version_no IS NULL OR version_no < 1;

CREATE INDEX IF NOT EXISTS idx_imports_company_period_version
  ON imports(company_id, period, version_no DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_imports_company_period_content_hash
  ON imports(company_id, period, content_hash);

CREATE INDEX IF NOT EXISTS idx_imports_company_period_normalized_hash
  ON imports(company_id, period, normalized_hash);

CREATE INDEX IF NOT EXISTS idx_imports_company_period_applied_at
  ON imports(company_id, period, applied_at DESC);
