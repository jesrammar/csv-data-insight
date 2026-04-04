ALTER TABLE universal_views
  ADD COLUMN source_universal_import_id BIGINT NULL REFERENCES universal_imports(id);

CREATE INDEX IF NOT EXISTS idx_universal_views_source_import
  ON universal_views(source_universal_import_id);

