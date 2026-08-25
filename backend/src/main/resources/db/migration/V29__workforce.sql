CREATE TABLE workforce_imports (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  filename TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  row_count INT NOT NULL,
  warning_count INT NOT NULL,
  error_count INT NOT NULL,
  error_summary TEXT,
  summary_json TEXT NOT NULL
);

CREATE INDEX idx_workforce_imports_company_created_at ON workforce_imports(company_id, created_at DESC);
