-- orazaka-packs#138 / ADR-087: per-CALL rates for external checks and grounded research. Applied once to an existing
-- billing database, as its owner role. Idempotent: the partial unique index idx_pricebook_current makes a second run
-- (or a row an administrator already published for the same capability and model) a no-op.
INSERT INTO credit_pricebook (version, capability, model_name, unit, credits_per_unit, minimum_credits, estimate_credits) VALUES
(2, 'EXTERNAL_CHECK', 'mock',       'CALL', 50.0000, 5, 50),
(2, 'RESEARCH',       'law-corpus', 'CALL', 10.0000, 5, 10)
ON CONFLICT DO NOTHING;
