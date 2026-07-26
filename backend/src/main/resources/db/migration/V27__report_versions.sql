ALTER TABLE reports
  ADD COLUMN version_no INT NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX uq_reports_company_period_version
  ON reports(company_id, period, version_no);
