CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      TEXT NOT NULL UNIQUE,
    password   TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount          NUMERIC(12,2) NOT NULL,
    category        TEXT NOT NULL,
    description     TEXT,
    idempotency_key TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE transactions_y2025
    PARTITION OF transactions
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

CREATE TABLE transactions_y2026
    PARTITION OF transactions
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE INDEX idx_transactions_user_id    ON transactions (user_id);
CREATE INDEX idx_transactions_occurred_at ON transactions (occurred_at DESC);
