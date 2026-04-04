-- H2 compatibility helpers for PostgreSQL-flavoured migrations.
-- Enables usage of `timestamptz` in migrations when running tests on H2.

create domain if not exists timestamptz as timestamp with time zone;

