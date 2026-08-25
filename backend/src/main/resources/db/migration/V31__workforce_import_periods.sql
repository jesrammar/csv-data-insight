ALTER TABLE workforce_imports
ADD COLUMN reference_period VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
ADD COLUMN reference_year INT,
ADD COLUMN reference_month INT,
ADD COLUMN coverage_start_month INT,
ADD COLUMN coverage_end_month INT,
ADD COLUMN coverage_complete_year BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN import_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY company_id, import_kind, reference_period
           ORDER BY created_at DESC, id DESC
         ) AS rn
  FROM workforce_imports
)
UPDATE workforce_imports wi
SET import_status = CASE WHEN ranked.rn = 1 THEN 'ACTIVE' ELSE 'SUPERSEDED' END
FROM ranked
WHERE wi.id = ranked.id;

CREATE INDEX idx_workforce_imports_company_kind_status_created_at
ON workforce_imports(company_id, import_kind, import_status, created_at DESC);

CREATE INDEX idx_workforce_imports_company_kind_period_created_at
ON workforce_imports(company_id, import_kind, reference_period, created_at DESC);

CREATE UNIQUE INDEX ux_workforce_imports_active_period
ON workforce_imports(company_id, import_kind, reference_period)
WHERE import_status = 'ACTIVE';
