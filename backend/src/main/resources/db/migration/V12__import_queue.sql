ALTER TABLE imports ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE imports ADD COLUMN run_after TIMESTAMP;
ALTER TABLE imports ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE imports ADD COLUMN max_attempts INT NOT NULL DEFAULT 3;
ALTER TABLE imports ADD COLUMN last_error TEXT;
ALTER TABLE imports ADD COLUMN storage_ref TEXT;
ALTER TABLE imports ADD COLUMN original_filename TEXT;
ALTER TABLE imports ADD COLUMN content_type TEXT;

UPDATE imports
SET updated_at = COALESCE(updated_at, created_at),
    run_after = COALESCE(run_after, created_at)
WHERE updated_at IS NULL OR run_after IS NULL;

CREATE INDEX IF NOT EXISTS idx_imports_status_runafter ON imports(status, run_after, id);
