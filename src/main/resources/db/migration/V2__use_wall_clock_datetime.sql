-- Business date-times represent Asia/Shanghai wall time. DATETIME avoids implicit
-- MySQL TIMESTAMP conversions when the JVM and database run in different zones.
ALTER TABLE campus_event MODIFY COLUMN starts_at DATETIME NOT NULL;
ALTER TABLE campus_event MODIFY COLUMN ends_at DATETIME NOT NULL;
ALTER TABLE campus_event MODIFY COLUMN registration_deadline DATETIME NOT NULL;
ALTER TABLE registration MODIFY COLUMN registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE registration MODIFY COLUMN checked_in_at DATETIME NULL;
