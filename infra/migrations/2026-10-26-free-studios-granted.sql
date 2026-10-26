-- Two Studios ship FREE (orazaka-packs: business-brief/invitation-writer, identity-document-check) and no plan granted them:
-- locked for every actor on every plan (orazaka SeedBootstrapIT.everyFreeStudioIsGrantedByAPlan). Granted on every plan, like the
-- other FREE Studios. Applied once to an existing billing database; idempotent. Mirrored in infra/initdb/70-billing.sql.
INSERT INTO billing_plan_entitlement (plan_key, entitlement_key, value_type, value)
SELECT plan_key, 'studio.' || studio, 'boolean', 'true'
  FROM (VALUES ('free'), ('premium'), ('ultimate')) AS plans(plan_key)
 CROSS JOIN (VALUES ('invitation-writer'), ('identity-document-check')) AS studios(studio)
ON CONFLICT (plan_key, entitlement_key) DO NOTHING;
