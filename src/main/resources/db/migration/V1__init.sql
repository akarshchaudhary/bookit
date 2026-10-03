CREATE TABLE users (
    id          UUID PRIMARY KEY,
    api_token   VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL
);

CREATE TABLE shows (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    price_paise     BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit  INT NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE reservations (
    id            UUID PRIMARY KEY,
    show_id       UUID NOT NULL REFERENCES shows(id),
    user_id       UUID NOT NULL REFERENCES users(id),
    amount_paise  BIGINT NOT NULL CHECK (amount_paise >= 0),
    seat_labels   TEXT NOT NULL,
    status        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cancelled_at  TIMESTAMPTZ NULL
);

CREATE INDEX idx_reservations_show_user ON reservations(show_id, user_id);

CREATE TABLE seats (
    id              UUID PRIMARY KEY,
    show_id         UUID NOT NULL REFERENCES shows(id),
    label           VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    reservation_id  UUID NULL REFERENCES reservations(id),
    CONSTRAINT uq_seats_show_label UNIQUE (show_id, label)
);

CREATE INDEX idx_seats_show_status ON seats(show_id, status);
CREATE INDEX idx_seats_reservation ON seats(reservation_id);

CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY,
    show_id          UUID NOT NULL REFERENCES shows(id),
    user_id          UUID NOT NULL REFERENCES users(id),
    idempotency_key  VARCHAR(128) NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    reservation_id   UUID NOT NULL REFERENCES reservations(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency UNIQUE (show_id, user_id, idempotency_key)
);

-- Seed 500 demo users for burst / concurrency testing (tokens: user-token-001 .. user-token-500)
INSERT INTO users (id, api_token, display_name)
SELECT
    ('11111111-1111-1111-1111-' || lpad(to_hex(n), 12, '0'))::uuid,
    'user-token-' || lpad(n::text, 3, '0'),
    'user-' || lpad(n::text, 3, '0')
FROM generate_series(1, 500) AS n;
