-- krizaka-billing#80 (krizaka/.github#446): offers as versioned data.
-- Applied once to an existing billing database, as its owner role. Idempotent. Mirrored in infra/initdb/70-billing.sql.
ALTER TABLE billing_plan ADD COLUMN IF NOT EXISTS overage_credit_price_cents INT;
ALTER TABLE billing_plan ADD COLUMN IF NOT EXISTS trial_days INT NOT NULL DEFAULT 0;
ALTER TABLE billing_plan ADD COLUMN IF NOT EXISTS free_monthly_credits BIGINT;
ALTER TABLE billing_subscription ADD COLUMN IF NOT EXISTS price_version_id UUID;

-- Each plan's current price becomes the first version of its price key (never overwrites an existing version).
INSERT INTO billing_price (price_key, unit, amount_cents, currency, availability, limits, effective_from, created_by)
SELECT 'plan.' || plan_key, 'month', price_cents, currency,
       CASE WHEN is_public THEN 'public' ELSE 'internal' END, '{}'::jsonb, '1970-01-01', 'migration'
FROM billing_plan
ON CONFLICT (price_key, effective_from) DO NOTHING;

-- Held subscriptions keep the price they run at today.
UPDATE billing_subscription s SET price_version_id = (
    SELECT p.version_id FROM billing_price p
    WHERE p.price_key = 'plan.' || s.plan_key AND p.effective_from <= now()
    ORDER BY p.effective_from DESC LIMIT 1)
WHERE s.price_version_id IS NULL;
