UPDATE users
SET password_hash = '$2a$10$aCE6f8wOfxMCL24jiurnvemAeeiZSWgoYTY1LJQZsoe9fbjR6UMKG'
WHERE email IN ('admin@asecon.local', 'consultor@asecon.local', 'cliente@acme.local');

