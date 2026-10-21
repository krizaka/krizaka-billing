-- ADR-085: billing's operator audit. Applied once to an existing billing database, as its owner role. Idempotent.
CREATE TABLE IF NOT EXISTS operator_audit (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id VARCHAR(255),
    actor_label VARCHAR(255) NOT NULL,
    action VARCHAR(120) NOT NULL,
    target_id VARCHAR(255),
    request_id VARCHAR(64),
    outcome VARCHAR(12) NOT NULL DEFAULT 'ok'
);
CREATE INDEX IF NOT EXISTS idx_operator_audit_at ON operator_audit(at DESC);
