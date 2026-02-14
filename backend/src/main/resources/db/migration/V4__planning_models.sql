CREATE TABLE business_lines (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL
);

CREATE TABLE cost_categories (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(16) NOT NULL
);

CREATE TABLE budgets (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  period VARCHAR(7) NOT NULL,
  metric VARCHAR(16) NOT NULL,
  amount NUMERIC(18,2) NOT NULL,
  category_id BIGINT REFERENCES cost_categories(id),
  business_line_id BIGINT REFERENCES business_lines(id)
);

CREATE TABLE forecasts (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  period VARCHAR(7) NOT NULL,
  metric VARCHAR(16) NOT NULL,
  amount NUMERIC(18,2) NOT NULL,
  source VARCHAR(16) NOT NULL
);

CREATE TABLE line_profits (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  business_line_id BIGINT NOT NULL REFERENCES business_lines(id) ON DELETE CASCADE,
  period VARCHAR(7) NOT NULL,
  amount NUMERIC(18,2) NOT NULL
);

CREATE TABLE company_finance_config (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL UNIQUE REFERENCES companies(id) ON DELETE CASCADE,
  fixed_costs NUMERIC(18,2),
  variable_cost_ratio NUMERIC(5,4),
  discount_rate NUMERIC(5,4)
);

CREATE INDEX idx_budget_company_period ON budgets(company_id, period);
CREATE INDEX idx_forecast_company_period ON forecasts(company_id, period);
CREATE INDEX idx_line_profit_company_period ON line_profits(company_id, period);
