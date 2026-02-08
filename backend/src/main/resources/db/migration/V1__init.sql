CREATE TABLE companies (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  plan VARCHAR(32) NOT NULL
);

CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL
);

CREATE TABLE user_companies (
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, company_id)
);

CREATE TABLE imports (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  period VARCHAR(7) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  processed_at TIMESTAMP,
  error_summary TEXT,
  warning_count INT,
  error_count INT
);

CREATE TABLE staging_transactions (
  id BIGSERIAL PRIMARY KEY,
  import_id BIGINT NOT NULL REFERENCES imports(id) ON DELETE CASCADE,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  txn_date DATE NOT NULL,
  description TEXT NOT NULL,
  amount NUMERIC(18,2) NOT NULL,
  counterparty TEXT,
  raw_json TEXT
);

CREATE TABLE transactions (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  period VARCHAR(7) NOT NULL,
  txn_date DATE NOT NULL,
  description TEXT NOT NULL,
  amount NUMERIC(18,2) NOT NULL,
  counterparty TEXT
);

CREATE TABLE kpi_monthly (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  period VARCHAR(7) NOT NULL,
  inflows NUMERIC(18,2) NOT NULL,
  outflows NUMERIC(18,2) NOT NULL,
  net_flow NUMERIC(18,2) NOT NULL,
  ending_balance NUMERIC(18,2) NOT NULL
);

CREATE TABLE alert_rules (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  net_flow_min_threshold NUMERIC(18,2) NOT NULL
);

CREATE TABLE alerts (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  period VARCHAR(7) NOT NULL,
  type VARCHAR(64) NOT NULL,
  message TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE reports (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  period VARCHAR(7) NOT NULL,
  format VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  storage_ref TEXT
);

CREATE INDEX idx_transactions_company_period ON transactions(company_id, period);
CREATE INDEX idx_kpi_company_period ON kpi_monthly(company_id, period);
CREATE INDEX idx_imports_company ON imports(company_id);
CREATE INDEX idx_reports_company ON reports(company_id);
