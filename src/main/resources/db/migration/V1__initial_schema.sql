CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(40) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'STUDENT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_role CHECK (role IN ('STUDENT', 'ADMIN'))
);

CREATE TABLE campus_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    category VARCHAR(20) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    location VARCHAR(100) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    registration_deadline TIMESTAMP NOT NULL,
    capacity INT NOT NULL,
    reserved_count INT NOT NULL DEFAULT 0,
    checkin_code VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT ck_capacity CHECK (capacity > 0 AND reserved_count >= 0 AND reserved_count <= capacity),
    CONSTRAINT ck_event_dates CHECK (ends_at > starts_at AND registration_deadline <= starts_at)
);
CREATE INDEX idx_event_starts ON campus_event(starts_at);
CREATE INDEX idx_event_category ON campus_event(category, starts_at);

CREATE TABLE registration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REGISTERED',
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_in_at TIMESTAMP NULL,
    CONSTRAINT uq_registration UNIQUE(event_id, user_id),
    CONSTRAINT fk_registration_event FOREIGN KEY (event_id) REFERENCES campus_event(id),
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT ck_registration_status CHECK (status IN ('REGISTERED', 'CANCELLED', 'ATTENDED'))
);
CREATE INDEX idx_registration_user ON registration(user_id, registered_at);
