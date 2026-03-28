CREATE TABLE tribunal_imports (
  id BIGSERIAL PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  filename TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  row_count INT NOT NULL,
  warning_count INT NOT NULL,
  error_count INT NOT NULL
);

CREATE TABLE tribunal_clients (
  id BIGSERIAL PRIMARY KEY,
  import_id BIGINT NOT NULL REFERENCES tribunal_imports(id) ON DELETE CASCADE,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  row_id INT,
  tipo_cliente VARCHAR(32),
  cliente TEXT,
  cif VARCHAR(32),
  administrador TEXT,
  dni_nie VARCHAR(32),
  minutas NUMERIC(18,2),
  f_alta DATE,
  f_baja DATE,
  f_pago VARCHAR(32),
  gestor VARCHAR(64),
  cont_modelos BOOLEAN,
  is_irpf_ok BOOLEAN,
  is_irpf_status VARCHAR(64),
  ddcc_ok BOOLEAN,
  ddcc_status VARCHAR(64),
  libros_ok BOOLEAN,
  libros_status VARCHAR(64),
  carga_de_trabajo NUMERIC(18,2),
  pct_contabilidad NUMERIC(18,2),
  promedio NUMERIC(18,2)
);

CREATE TABLE tribunal_activity (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL REFERENCES tribunal_clients(id) ON DELETE CASCADE,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  year INT NOT NULL,
  n_as INT NOT NULL
);

CREATE INDEX idx_tribunal_clients_company ON tribunal_clients(company_id);
CREATE INDEX idx_tribunal_activity_company_year ON tribunal_activity(company_id, year);
