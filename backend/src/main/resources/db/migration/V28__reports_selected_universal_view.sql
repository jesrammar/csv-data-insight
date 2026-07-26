ALTER TABLE reports ADD COLUMN selected_universal_view_id BIGINT;

ALTER TABLE reports ADD COLUMN selected_universal_view_name VARCHAR(255);

ALTER TABLE reports ADD COLUMN selected_universal_aggregation_mode VARCHAR(64);
