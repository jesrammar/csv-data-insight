CREATE TABLE universal_imports (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  filename TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  row_count INT NOT NULL,
  column_count INT NOT NULL,
  summary_json TEXT NOT NULL
);

CREATE INDEX idx_universal_imports_company ON universal_imports(company_id);
