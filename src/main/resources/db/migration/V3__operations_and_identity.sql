ALTER TABLE campus_event ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE campus_event ADD COLUMN cancellation_reason VARCHAR(300) NULL;
ALTER TABLE campus_event ADD COLUMN checkin_expires_at DATETIME NULL;
ALTER TABLE campus_event ADD CONSTRAINT ck_event_status CHECK (status IN ('PUBLISHED', 'CANCELLED'));
ALTER TABLE app_user ADD COLUMN campus_id VARCHAR(32) NULL;
ALTER TABLE app_user ADD COLUMN verified_at DATETIME NULL;
CREATE UNIQUE INDEX uq_user_campus_id ON app_user(campus_id);

CREATE TABLE operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    subject_id BIGINT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    occurred_at DATETIME NOT NULL,
    CONSTRAINT fk_log_event FOREIGN KEY (event_id) REFERENCES campus_event(id),
    CONSTRAINT fk_log_actor FOREIGN KEY (actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_log_event ON operation_log(event_id, id);
CREATE INDEX idx_log_subject ON operation_log(subject_id, id);
CREATE INDEX idx_attendees_page ON registration(event_id, registered_at, id);
CREATE INDEX idx_bookings_page ON registration(user_id, registered_at, id);
