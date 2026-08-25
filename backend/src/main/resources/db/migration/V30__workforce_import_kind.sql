ALTER TABLE workforce_imports
ADD COLUMN import_kind VARCHAR(32) NOT NULL DEFAULT 'WORKFORCE';

CREATE INDEX idx_workforce_imports_company_kind_created_at
ON workforce_imports(company_id, import_kind, created_at DESC);
