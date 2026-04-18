alter table company_settings
  add column if not exists report_consultancy_name varchar(140);

alter table company_settings
  add column if not exists report_logo_url varchar(500);

alter table company_settings
  add column if not exists report_primary_color varchar(16);

alter table company_settings
  add column if not exists report_footer_text varchar(400);
