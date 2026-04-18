INSERT INTO companies (id, name, plan) VALUES
  (1, 'ACME Retail', 'GOLD'),
  (2, 'Nova Logistics', 'BRONZE');

INSERT INTO users (id, email, password_hash, role, enabled) VALUES
  (1, 'admin@asecon.local', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5w8QdVvF3y2CIy9f3rFh2Y7f1K8a6', 'ADMIN', true),
  (2, 'consultor@asecon.local', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5w8QdVvF3y2CIy9f3rFh2Y7f1K8a6', 'CONSULTOR', true),
  (3, 'cliente@acme.local', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5w8QdVvF3y2CIy9f3rFh2Y7f1K8a6', 'CLIENTE', true);

INSERT INTO user_companies (user_id, company_id) VALUES
  (1, 1), (1, 2),
  (2, 1), (2, 2),
  (3, 1);

INSERT INTO alert_rules (id, company_id, net_flow_min_threshold) VALUES
  (1, 1, -5000),
  (2, 2, -2500);
