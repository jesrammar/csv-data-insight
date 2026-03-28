-- Faster ordered reads and period-based maintenance.
-- Keeps existing idx_transactions_company_period (V1__init.sql) intact.

CREATE INDEX IF NOT EXISTS idx_transactions_company_period_date
  ON transactions(company_id, period, txn_date);

CREATE INDEX IF NOT EXISTS idx_transactions_company_date
  ON transactions(company_id, txn_date);

