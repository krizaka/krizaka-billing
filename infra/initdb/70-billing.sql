-- ============================================================================
-- ORAZAKA — Local DB bootstrap · 70 — BILLING & CREDITS CONTEXT
-- ----------------------------------------------------------------------------
-- Owner: Billing service (krizaka-billing-service :8095 — ADR-033).
-- Everything "what may this actor buy, and how much volume is left": plans,
-- entitlements, packs métier and who owns them, subscriptions, the credit ledger,
-- holds, the versioned pricebook, metered usage, and this service's own runtime
-- config + outbox + dedup ledger.
-- actor_id is an OPAQUE ActorId copied by value from the identity context — no
-- inbound or outbound cross-context FK (SEAM-001); rate_limit_tier_key is an
-- opaque reference into identity's tier catalogue, likewise never an FK.
-- These tables live in their OWN database (krizaka_billing_db) under the
-- service's own role, created here.
-- ============================================================================

-- The password is NOT set here. psql 15 cannot read the environment (\getenv is 16+)
-- and ERR-125 bans a shell script, so `orazaka start` applies ALTER ROLE from
-- BILLING_DB_PASSWORD once the container is healthy. A role created without a
-- password cannot authenticate, so a skipped step fails closed rather than leaving a
-- guessable one — which is what the committed literal was (ADR-035, audit #5).
CREATE ROLE krizaka_billing LOGIN;
CREATE DATABASE krizaka_billing_db OWNER krizaka_billing;
\c krizaka_billing_db
SET ROLE krizaka_billing;

-- ── Commercial plane (design §5.1) ──────────────────────────────────────────
-- A plan is a ROW. Adding "ultimate+" or "studio" is an admin action, never a deploy.
CREATE TABLE billing_plan (
    plan_key            VARCHAR(50)  PRIMARY KEY,       -- free | premium | ultimate | …
    label               VARCHAR(100) NOT NULL,
    tier_rank           INT          NOT NULL,          -- ordering for upgrade/downgrade logic
    monthly_credit_grant BIGINT      NOT NULL DEFAULT 0,-- credits granted at each period start
    price_cents         INT          NOT NULL DEFAULT 0,
    currency            CHAR(3)      NOT NULL DEFAULT 'EUR',
    rate_limit_tier_key VARCHAR(50)  NOT NULL,          -- OPAQUE ref into identity — no FK
    external_plan_code  VARCHAR(100),                   -- Lago plan code; NULL while local-only
    is_public           BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Entitlement = typed key/value. New entitlement keys need no schema change.
CREATE TABLE billing_plan_entitlement (
    plan_key        VARCHAR(50)  NOT NULL REFERENCES billing_plan(plan_key) ON DELETE CASCADE,
    entitlement_key VARCHAR(120) NOT NULL,   -- capability.video · concurrency.jobs · model.class · package.marketing
    value_type      VARCHAR(20)  NOT NULL,   -- boolean | int | string
    value           VARCHAR(255) NOT NULL,
    PRIMARY KEY (plan_key, entitlement_key)
);

-- Packs métier: same entitlement grammar as a plan, bought separately from one.
--
-- This table used to carry `label`, `category` and `profession` too, and the category was
-- a CHECK rather than a catalogue table. The comment here defended that choice on the
-- grounds that "a shelf nobody can name in the front-end is a shelf nobody browses".
-- The reasoning was right; the conclusion no longer holds, and that is worth writing down
-- so the next reader does not re-derive it. A category needed a French label, an icon and
-- a sort order the moment "Life Style" had to sit next to "Business" in a marketplace —
-- which is precisely the nameability the comment was protecting. A CHECK cannot carry any
-- of the three.
--
-- So the catalogue moved to `pack` / `pack_i18n` / `pack_category` / `pack_category_i18n`
-- in 80-studio.sql (ADR-036), where studio_i18n already provides the one i18n mechanism
-- this platform has. What is left here is the price tag:
--   pack_key · price_cents · included_credits · external_plan_code · is_active
-- Billing answers "what does it cost and what does it grant". The catalogue answers "what
-- is it and what does it do". pack_key is the same OPAQUE string in both databases and
-- there is no FK between them (SEAM-001) — a marketing copy change must never be a deploy
-- of the service that holds the credit ledger.
CREATE TABLE billing_pack (
    pack_key           VARCHAR(50)  PRIMARY KEY,   -- OPAQUE, mirrored in studio's `pack` — no FK
    price_cents        INT          NOT NULL DEFAULT 0,
    included_credits   BIGINT       NOT NULL DEFAULT 0,
    external_plan_code VARCHAR(100),
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE
);
-- No browse index: browsing is the catalogue's query now, and it runs against
-- pack(category_key, sort_weight) in the studio database. What is left here is a
-- primary-key lookup per pack key, which the PK already serves.

CREATE TABLE billing_pack_entitlement (
    pack_key     VARCHAR(50)  NOT NULL REFERENCES billing_pack(pack_key) ON DELETE CASCADE,
    entitlement_key VARCHAR(120) NOT NULL,
    value_type      VARCHAR(20)  NOT NULL,
    value           VARCHAR(255) NOT NULL,
    PRIMARY KEY (pack_key, entitlement_key)
);

-- The actor↔pack link — what makes the design's "plan ∪ pack" union (§14) representable.
-- Without this table a pack could be authored and priced but never owned, so every
-- entitlement it grants was unreachable and every PAID Studio was uninstallable.
-- Deliberately NOT folded into billing_subscription: that table's partial unique index is
-- "one live subscription per actor", which is right for a plan and wrong for packs — an
-- actor holds any number of packs at once, and exactly one plan.
CREATE TABLE billing_pack_subscription (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id                 VARCHAR(255) NOT NULL,   -- OPAQUE — no FK into identity
    pack_key                 VARCHAR(50)  NOT NULL REFERENCES billing_pack(pack_key),
    status                   VARCHAR(30)  NOT NULL,   -- TRIALING|ACTIVE|PAST_DUE|CANCELED|EXPIRED
    subscribed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    period_end               TIMESTAMPTZ,             -- NULL = perpetual, the one-off purchase case
    external_subscription_id VARCHAR(255),            -- Lago; NULL while local-only
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- "One live subscription per actor PER PACK", enforced by the database rather than by a
-- read-before-write in Java (ERR-109): a double-submitted subscribe hits the index, not a race.
CREATE UNIQUE INDEX idx_pack_subscription_live
    ON billing_pack_subscription(actor_id, pack_key) WHERE status IN ('TRIALING','ACTIVE','PAST_DUE');
-- The entitlement read is per actor and sits on the authorisation hot path.
CREATE INDEX idx_pack_subscription_actor
    ON billing_pack_subscription(actor_id) WHERE status IN ('TRIALING','ACTIVE','PAST_DUE');
CREATE INDEX idx_pack_subscription_pack ON billing_pack_subscription(pack_key, status);

CREATE TABLE billing_subscription (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id              VARCHAR(255) NOT NULL,   -- OPAQUE — no FK into identity
    plan_key              VARCHAR(50)  NOT NULL REFERENCES billing_plan(plan_key),
    status                VARCHAR(30)  NOT NULL,   -- TRIALING|ACTIVE|PAST_DUE|CANCELED|EXPIRED
    period_start          TIMESTAMPTZ  NOT NULL,
    period_end            TIMESTAMPTZ  NOT NULL,
    cancel_at_period_end  BOOLEAN      NOT NULL DEFAULT FALSE,
    external_subscription_id VARCHAR(255),         -- Lago; NULL while local-only
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- "One active subscription per actor", enforced by the database rather than by a
-- read-before-write in Java (ERR-109).
CREATE UNIQUE INDEX idx_billing_subscription_active
    ON billing_subscription(actor_id) WHERE status IN ('TRIALING','ACTIVE','PAST_DUE');

-- ── Credit plane — the ledger is the source of truth (design §5.2) ──────────
-- Materialised balance. Two buckets with DIFFERENT expiry semantics (design §6.4).
CREATE TABLE credit_wallet (
    actor_id          VARCHAR(255) PRIMARY KEY,
    balance_granted   BIGINT      NOT NULL DEFAULT 0,  -- from the subscription; expires at period end
    balance_purchased BIGINT      NOT NULL DEFAULT 0,  -- bought à la carte; long/never expiry
    held              BIGINT      NOT NULL DEFAULT 0,  -- sum of ACTIVE holds — denormalised for O(1) authz
    version           BIGINT      NOT NULL DEFAULT 0,  -- optimistic lock
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wallet_non_negative
        CHECK (balance_granted >= 0 AND balance_purchased >= 0 AND held >= 0)
);

-- APPEND-ONLY. No UPDATE, no DELETE, ever. This is the financial record.
CREATE TABLE credit_ledger_entry (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id         VARCHAR(255) NOT NULL,
    entry_type       VARCHAR(20)  NOT NULL,  -- GRANT|PURCHASE|DEBIT|REFUND|EXPIRY|ADJUSTMENT
    bucket           VARCHAR(20)  NOT NULL,  -- GRANTED|PURCHASED
    amount           BIGINT       NOT NULL,  -- signed: +credit, -debit
    balance_after    BIGINT       NOT NULL,  -- bucket balance after this entry — audit anchor
    reference_type   VARCHAR(50),            -- HOLD|JOB|SUBSCRIPTION|TOPUP|ADMIN
    reference_id     VARCHAR(255),
    idempotency_key  VARCHAR(255) NOT NULL,  -- AMQP messageId, hold id, PSP event id…
    reason           VARCHAR(255),
    created_by       VARCHAR(255) NOT NULL,  -- actor or 'system' — every ADJUSTMENT is attributable
    -- Which credit unit this row is denominated in (ADR-047). Default 2 because a fresh database
    -- starts after the rescale; a database that predates it carries 1 on its historical rows,
    -- labelled by infra/migrations/2026-09-03-billing-credit-scale-10x.sql. The epoch is a
    -- property of WHEN a row was written, so the database sets it and no producer can forget to.
    -- Amounts are NEVER rewritten across a rescale — the append-only trigger below is the reason
    -- the unit has to be recorded instead.
    unit_version     INT          NOT NULL DEFAULT 2,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- The whole at-least-once story: a redelivered settlement inserts a duplicate key, the
-- insert fails, the consumer treats that as "already applied" and acks (ERR-109).
CREATE UNIQUE INDEX idx_ledger_idempotency ON credit_ledger_entry(idempotency_key);
CREATE INDEX idx_ledger_actor_time ON credit_ledger_entry(actor_id, created_at DESC);

-- Append-only is enforced by a TRIGGER, not by REVOKE: the table owner keeps implicit
-- rights on its own table, so a grant-based rule is not actually a constraint.
CREATE OR REPLACE FUNCTION credit_ledger_immutable() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'credit_ledger_entry is append-only (ADR-033) — % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_credit_ledger_immutable
  BEFORE UPDATE OR DELETE ON credit_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION credit_ledger_immutable();

-- The reservation. Its TTL is what prevents leaked credits when a worker dies.
CREATE TABLE credit_hold (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id           VARCHAR(255) NOT NULL,
    estimated_credits  BIGINT       NOT NULL,
    settled_credits    BIGINT,
    status             VARCHAR(20)  NOT NULL,  -- ACTIVE|SETTLED|RELEASED|EXPIRED
    capability         VARCHAR(50)  NOT NULL,  -- CHAT|IMAGE|AUDIO|VIDEO|AGENT (mirrors business.api.Capability, contract-copied)
    model_name         VARCHAR(255),
    pricebook_version  INT          NOT NULL,  -- pin the rate: a mid-flight price change cannot corrupt settlement
    correlation_id     VARCHAR(255) NOT NULL,  -- Intention.id — ties a hold to its whole agent run
    job_id             VARCHAR(36),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    settled_at         TIMESTAMPTZ
);
CREATE INDEX idx_hold_sweeper ON credit_hold(expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_hold_job ON credit_hold(job_id) WHERE job_id IS NOT NULL;

-- ── Pricing plane — versioned, DB-driven (design §5.3, ADR-027/031) ────────
-- A row is NEVER updated in place: a price change closes the current row (effective_to)
-- and inserts a new version. Holds pin the version they were priced with.
CREATE TABLE credit_pricebook (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           INT          NOT NULL,
    capability        VARCHAR(50)  NOT NULL,
    model_name        VARCHAR(255),             -- NULL = default rate for the capability
    unit              VARCHAR(30)  NOT NULL,    -- KILOTOKEN|IMAGE_STEP|OUTPUT_SECOND|KILOCHAR|AUDIO_MINUTE|GPU_SECOND|CALL
    credits_per_unit  NUMERIC(12,4) NOT NULL,
    minimum_credits   BIGINT       NOT NULL DEFAULT 1,   -- floor: no free ride on tiny requests
    estimate_credits  BIGINT       NOT NULL,             -- what the HOLD reserves before execution
    effective_from    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    effective_to      TIMESTAMPTZ,
    CONSTRAINT ck_pricebook_positive CHECK (credits_per_unit >= 0)
);
-- One current rate per capability × model. `unit` is deliberately NOT in the key: the
-- metering unit is a property of the model (Whisper bills minutes, Piper bills characters —
-- no model bills both), so it is an output of the lookup, not an input. Adding it would make
-- a bare (capability, model) resolution ambiguous at hold time (design §5.3/§8).
CREATE UNIQUE INDEX idx_pricebook_current
    ON credit_pricebook(capability, COALESCE(model_name, '*')) WHERE effective_to IS NULL;

-- The metering fact table: one row per measured consumption. Feeds Lago, analytics and disputes.
CREATE TABLE usage_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id         VARCHAR(255) NOT NULL,
    capability       VARCHAR(50)  NOT NULL,
    model_name       VARCHAR(255),
    unit             VARCHAR(30)  NOT NULL,
    quantity         NUMERIC(14,4) NOT NULL,
    credits_charged  BIGINT       NOT NULL,
    hold_id          UUID,
    job_id           VARCHAR(36),
    correlation_id   VARCHAR(255) NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    -- The cost side of the margin. On owned hardware the accelerator is occupied for the whole
    -- execution, so wall clock is the only honest cost signal (design §7) — and margin per
    -- capability is unanswerable without storing it next to what was charged.
    gpu_seconds      NUMERIC(12,3),
    external_synced_at TIMESTAMPTZ                 -- NULL until Lago acknowledged it
);
CREATE INDEX idx_usage_unsynced ON usage_event(occurred_at) WHERE external_synced_at IS NULL;
CREATE INDEX idx_usage_actor_time ON usage_event(actor_id, occurred_at DESC);

-- Refusals. The 402 rate is the pricing-health metric (design §12): a high one means the
-- price is wrong or the paywall sits in the wrong place, and neither is visible from
-- usage_event, which by construction only records requests that succeeded.
-- `enforced` is what makes DRY_RUN worth running: under shadow metering the request is
-- granted anyway, and this row is the record of what enforcement WOULD have refused —
-- the calibration evidence phase 0 exists to collect.
CREATE TABLE credit_refusal (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id       VARCHAR(255) NOT NULL,   -- OPAQUE — no FK into identity
    capability     VARCHAR(50)  NOT NULL,
    model_name     VARCHAR(255),
    required       BIGINT       NOT NULL,   -- what the request would have cost
    available      BIGINT       NOT NULL,   -- what the actor could actually cover
    enforced       BOOLEAN      NOT NULL,   -- TRUE = actually refused; FALSE = DRY_RUN shadow
    correlation_id VARCHAR(255) NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refusal_capability_time ON credit_refusal(capability, occurred_at DESC);
CREATE INDEX idx_refusal_actor_time ON credit_refusal(actor_id, occurred_at DESC);

-- Work served without a hold because billing could not be reached (ADR-064). The credit gate
-- fails open, and chose to; what makes that a posture rather than a leak is that every such turn
-- lands here, from the serving service's outbox, once billing is back. Reconciliation — bill it
-- after the fact, or write it off — reads this table; nothing writes a debit from it on its own.
-- No quantity: the gate records before the work runs. correlation_id joins it to what it produced.
CREATE TABLE unmetered_turn (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id       VARCHAR(255) NOT NULL,   -- OPAQUE — no FK into identity
    capability     VARCHAR(50)  NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,   -- the conversation or job
    reason         VARCHAR(255) NOT NULL,   -- a failure's type, never its message
    occurred_at    TIMESTAMPTZ  NOT NULL,   -- when the hold was attempted, by the serving service
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_unmetered_turn_time ON unmetered_turn(occurred_at DESC);
CREATE INDEX idx_unmetered_turn_actor_time ON unmetered_turn(actor_id, occurred_at DESC);

-- ── Audit & plumbing (design §5.4) ─────────────────────────────────────────
-- Same shape as 60-governance's policy_version_history: every plan/pricebook mutation
-- is snapshotted and attributable. Price changes are a legal artefact, not a config tweak.
CREATE TABLE billing_version_history (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type  VARCHAR(50)  NOT NULL,   -- PLAN|PACK|PRICEBOOK
    entity_key   VARCHAR(120) NOT NULL,
    snapshot     JSONB        NOT NULL,
    sha256_hash  VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(255) NOT NULL
);

-- Billing transactional outbox (AGENTS.md §6) — identical contract to identity_outbox.
CREATE TABLE billing_outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    exchange VARCHAR(100) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    message_id UUID NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    -- AMQP headers published with the message: the event envelope (kz-type, kz-version,
    -- kz-producer, kz-correlation-id, kz-occurred-at) of a row written by an EventPublisher.
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_billing_outbox_pending ON billing_outbox(next_attempt_at) WHERE published_at IS NULL;

-- Consumer-side idempotency (AGENTS.md §6) — this service's OWN copy of the dedup
-- ledger, in its own database (contract-copy doctrine, no shared table).
CREATE TABLE processed_messages (
    consumer VARCHAR(100) NOT NULL,
    message_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- Post-startup behaviour an admin flips live (design §13.5) — this service's own copy
-- of the runtime-config shape. Seeded once; an admin change must survive the next
-- `orazaka start`, hence ON CONFLICT DO NOTHING rather than a reapplied file.
CREATE TABLE billing_runtime_config (
    config_key   VARCHAR(120) PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    value_type   VARCHAR(20)  NOT NULL DEFAULT 'string',
    description  TEXT
);

-- ============================================================================
-- BILLING SEED DATA (dev only, idempotent)
-- ============================================================================

INSERT INTO billing_runtime_config (config_key, config_value, value_type, description) VALUES
('billing.enforcement.mode',        'DRY_RUN',     'string', 'OFF | DRY_RUN | ENFORCING — master gate. DRY_RUN meters without blocking.'),
('billing.fail-mode.chat',          'FAIL_OPEN',   'string', 'Billing unreachable on the sync path: FAIL_OPEN protects UX, records a reconciliation debt.'),
('billing.fail-mode.media',         'FAIL_CLOSED', 'string', 'Billing unreachable on a high-cost job: FAIL_CLOSED protects margin.'),
('billing.hold.ttl-seconds',        '1800',        'int',    'Sweeper releases ACTIVE holds older than this (design §6.3).'),
('billing.credit.unit-price-millicents', '100',   'int',    'Money anchor: millicents per credit. 1 kilotoken = 10 credits x 100 = 1000 mc = 1 cent, unchanged across the v1->v2 rescale (ADR-047).'),
('billing.overshoot.max-credits',   '500',         'int',    'Bounded settlement overshoot allowed rather than killing a stream mid-token (design §17).'),
('billing.low-balance.percent',     '10',          'int',    'Threshold that emits evt.wallet.low-balance.'),
('billing.adjustment.daily-max-credits', '100000', 'int',    'Per-admin ceiling on manual ADJUSTMENT entries (design §12.1).')
ON CONFLICT (config_key) DO NOTHING;

-- Plans (design §8). Prices and grants are ADMIN DATA — editable live, never in yaml.
-- rate_limit_tier_key is an opaque reference into identity's rate_limits.
-- external_plan_code stays NULL: the Lago seam (phase 4) is gated on leaving the local phase.
INSERT INTO billing_plan (plan_key, label, tier_rank, monthly_credit_grant, price_cents, currency, rate_limit_tier_key, is_public, is_active) VALUES
('free',     'Free',     1,   500,    0, 'EUR', 'free',       TRUE, TRUE),
('premium',  'Premium',  2,  5000, 1900, 'EUR', 'premium',    TRUE, TRUE),
('ultimate', 'Ultimate', 3, 20000, 4900, 'EUR', 'enterprise', TRUE, TRUE)
ON CONFLICT (plan_key) DO NOTHING;

-- The design §8 entitlement matrix. Entitlement gates ACCESS; credits gate VOLUME —
-- a free actor is not entitled to video at any balance.
INSERT INTO billing_plan_entitlement (plan_key, entitlement_key, value_type, value) VALUES
('free',     'capability.chat',       'boolean', 'true'),
('free',     'capability.image',      'boolean', 'true'),
('free',     'capability.audio',      'boolean', 'false'),
('free',     'capability.video',      'boolean', 'false'),
('free',     'capability.agent',      'boolean', 'false'),
('free',     'capability.workflow',   'boolean', 'false'),
('free',     'capability.automation', 'boolean', 'false'),
('free',     'capability.api-keys',   'boolean', 'false'),
('free',     'concurrency.jobs',      'int',     '1'),
('free',     'model.class',           'string',  'local'),
('free',     'topup.enabled',         'boolean', 'false'),
-- A FREE Studio still needs its key granted on every plan: allows() reads an absent key
-- as a denial (documented behaviour, not a bug to code around), so an ungranted
-- studio.<key> would lock every actor out of a Studio that costs nothing (ADR-034 §8.2).
('free',     'studio.trade-showcase', 'boolean', 'true'),
-- ── The media TOOLKIT's six Studios, in the vocabulary StudioAccessService actually reads ──
-- ADR-066 turned media into a pack and ADR-061 made a TOOLKIT's installation DERIVED from its
-- entitlement. Nothing granted its studio.* keys, so on a fresh install every media button was
-- locked on every plan, including ultimate — `POST /studios/image-generation/runs` answered 409
-- for an actor whose plan says capability.image = true (ADR-069 §0.4, found by the e2e gate).
--
-- These are a TRANSCRIPTION, not a pricing decision: the capability.* rows above already say what
-- each plan may do, and each Studio inherits its own capability's value. free gets image and not
-- audio or video because that is what capability.image|audio|video say three rows up.
--
-- capability.image|audio|video are NOT removed and are not dead: they remain the grant read off
-- the media path, alongside concurrency.jobs and model.class, which are the same family of
-- plan-level statements. They are superseded for STUDIO ACCESS only — that question is answered
-- by studio.<key>, and these six rows are what makes the two agree.
--
-- The precedent is studio.trade-showcase directly above: allows() reads an absent key as a denial,
-- and the answer taken then was to GRANT the key in every plan rather than to route around
-- entitlement (ADR-034 §8.2). Exempting a TOOLKIT from entitlement would remove one of the four
-- controls M3 put in front of every run, which is what door 1 did.
('free',     'studio.image-generation', 'boolean', 'true'),
('free',     'studio.image-analysis',   'boolean', 'true'),
('free',     'studio.speech-synthesis', 'boolean', 'false'),
('free',     'studio.audio-analysis',   'boolean', 'false'),
('free',     'studio.video-generation', 'boolean', 'false'),
('free',     'studio.video-analysis',   'boolean', 'false'),
-- ── Free packs are granted by every plan, because nothing else grants them ──────────────────
-- A pack declaring `pricing.priceCents: 0` has no purchase to unlock it. Until this block,
-- echo-toolkit and bien-etre shipped PUBLISHED, installed into the catalogue, and were reachable
-- by nobody on any plan — `studio_not_entitled` on a fresh volume for every actor, including
-- `ultimate` (docs/evaluations/first-real-use.md, B2).
--
-- bien-etre being granted here does NOT make it installable: it is REGULATED, so the consent
-- gate, the age attestation and the sourced-region check still stand between the grant and an
-- installation. Entitlement gates ACCESS; those gate lawfulness, and they are separate on purpose.
--
-- document-validation and realestate-studio are deliberately NOT here: they declare 4900 cents,
-- and a plan row would make a sold pack free. SeedBootstrapIT reads `priceCents` for exactly that
-- distinction rather than carrying a list.
('free',     'studio.echo-reverse', 'boolean', 'true'),
('free',     'studio.guided-journaling', 'boolean', 'true'),
('free',     'studio.session-preparation', 'boolean', 'true'),
('free',     'studio.mood-tracking', 'boolean', 'true'),
('premium',  'capability.chat',       'boolean', 'true'),
('premium',  'capability.image',      'boolean', 'true'),
('premium',  'capability.audio',      'boolean', 'true'),
('premium',  'capability.video',      'boolean', 'true'),
('premium',  'capability.agent',      'boolean', 'true'),
('premium',  'capability.workflow',   'boolean', 'false'),
('premium',  'capability.automation', 'boolean', 'false'),
('premium',  'capability.api-keys',   'boolean', 'false'),
('premium',  'concurrency.jobs',      'int',     '5'),
('premium',  'model.class',           'string',  'premium'),
('premium',  'topup.enabled',         'boolean', 'true'),
('premium',  'studio.trade-showcase', 'boolean', 'true'),
-- INCLUDED (design §8.1): granted by the plan, not by a pack purchase.
('premium',  'studio.outbound-prospection', 'boolean', 'true'),
-- The prospection pack's other two Studios (ADR-043). INCLUDED pricing means "granted by the plan
-- the actor already pays for", so the plan matrix is where that grant lives — a pack does not get
-- to decide which plans include it. Adding a Studio to a plan is exactly this: one row, no deploy.
('premium',  'studio.lead-research', 'boolean', 'true'),
('premium',  'studio.followup-sequences', 'boolean', 'true'),
-- The media TOOLKIT: premium grants capability.audio and capability.video, so it grants all six.
('premium',  'studio.image-generation', 'boolean', 'true'),
('premium',  'studio.image-analysis',   'boolean', 'true'),
('premium',  'studio.speech-synthesis', 'boolean', 'true'),
('premium',  'studio.audio-analysis',   'boolean', 'true'),
('premium',  'studio.video-generation', 'boolean', 'true'),
('premium',  'studio.video-analysis',   'boolean', 'true'),
('premium',  'studio.echo-reverse', 'boolean', 'true'),
('premium',  'studio.guided-journaling', 'boolean', 'true'),
('premium',  'studio.session-preparation', 'boolean', 'true'),
('premium',  'studio.mood-tracking', 'boolean', 'true'),
('ultimate', 'capability.chat',       'boolean', 'true'),
('ultimate', 'capability.image',      'boolean', 'true'),
('ultimate', 'capability.audio',      'boolean', 'true'),
('ultimate', 'capability.video',      'boolean', 'true'),
('ultimate', 'capability.agent',      'boolean', 'true'),
('ultimate', 'capability.workflow',   'boolean', 'true'),
('ultimate', 'capability.automation', 'boolean', 'true'),
('ultimate', 'capability.api-keys',   'boolean', 'true'),
('ultimate', 'concurrency.jobs',      'int',     '20'),
('ultimate', 'model.class',           'string',  'all'),
('ultimate', 'topup.enabled',         'boolean', 'true'),
('ultimate', 'studio.trade-showcase', 'boolean', 'true'),
('ultimate', 'studio.outbound-prospection', 'boolean', 'true'),
('ultimate', 'studio.lead-research', 'boolean', 'true'),
('ultimate', 'studio.followup-sequences', 'boolean', 'true'),
-- The media TOOLKIT: ultimate grants all three capabilities, so it grants all six Studios.
('ultimate', 'studio.image-generation', 'boolean', 'true'),
('ultimate', 'studio.image-analysis',   'boolean', 'true'),
('ultimate', 'studio.speech-synthesis', 'boolean', 'true'),
('ultimate', 'studio.audio-analysis',   'boolean', 'true'),
('ultimate', 'studio.video-generation', 'boolean', 'true'),
('ultimate', 'studio.echo-reverse', 'boolean', 'true'),
('ultimate', 'studio.guided-journaling', 'boolean', 'true'),
('ultimate', 'studio.session-preparation', 'boolean', 'true'),
('ultimate', 'studio.mood-tracking', 'boolean', 'true'),
('ultimate', 'studio.video-analysis',   'boolean', 'true')
ON CONFLICT (plan_key, entitlement_key) DO NOTHING;

-- Packs métier (ADR-034 §8.1, ADR-036). A PAID Studio is unlocked by BUYING its pack,
-- not by upgrading a plan — which is why the entitlement lives here and not in the
-- plan matrix above. Adding a Studio is one pack row plus one entitlement row:
-- zero billing code, because EntitlementSnapshot reads arbitrary string keys.
--
-- No billing_pack / billing_pack_entitlement seed: a pack's price and its grants ship in its
-- bundle (ADR-037 phase D) and are written by `orazaka pack install`, which calls this service's
-- /internal/v1/billing/packs surface before the catalogue rows that depend on them.
--
-- realestate-studio's 4900 cents / 5000 credits and its studio.realestate-reels grant used to be
-- seeded here; they are now orazaka-packs/realestate-studio/pack.yaml, transcribed value for
-- value. The ordering matters more than the location: the installer provisions billing FIRST,
-- because a pack_studio row whose matching grant does not exist locks out exactly the customer
-- who just paid (ADR-036, invariant #3). Seeding both halves in two files that are applied in
-- alphabetical order made that ordering a coincidence of filenames; it is now a line of code.

-- Pricebook version 1 (design §8, anchored on 1 credit = 1k tokens on the default local
-- chat model). The key is (capability, model) — what the caller knows BEFORE the request
-- runs. Anything that varies per request (resolution, token count, duration) is carried by
-- the QUANTITY, never by an extra key column.
--
-- ⚠ THESE NUMBERS ARE PLACEHOLDERS TO BE RECALIBRATED IN PHASE 0 (design §7/§16): the
-- ratios are shaped correctly, the absolute calibration must come from two weeks of shadow
-- metering on this hardware. Never publish them as list prices as-is.
--
-- IMAGE: unit is a MEGAPIXEL-STEP — images × steps × (width × height ÷ 1e6) — because
-- resolution is a per-request payload field (the media worker reads width/height per call),
-- not a model property. credits_per_unit 1.9073 holds design §8's anchor of 10 credits for
-- 512² × 20 steps (5.24288 units). estimate_credits is sized for the shape the worker
-- actually defaults to (1024×576 @ 16 steps ≈ 18 credits), not for the 512² anchor.
INSERT INTO credit_pricebook (version, capability, model_name, unit, credits_per_unit, minimum_credits, estimate_credits) VALUES
(1, 'CHAT',  NULL, 'KILOTOKEN',      1.0000,  1,   2),
(1, 'IMAGE', NULL, 'IMAGE_STEP',     1.9073,  5,  20),
(1, 'VIDEO', NULL, 'OUTPUT_SECOND', 90.0000, 90, 360),
(1, 'AGENT', NULL, 'CALL',           5.0000,  5,   5),
-- Image ANALYSIS, priced apart from image GENERATION (ADR-041). Same capability, four engines,
-- and the unit differs because the work does: generation is denoising steps over megapixels,
-- analysis is a VLM completion whose image becomes prompt tokens. IMAGE_STEP cannot express it —
-- quantityFor(IMAGE_STEP) requires steps > 0, meaning DENOISING steps, which an analysis has
-- none of — so pricing vision there would mean inventing steps=1 to satisfy a unit. This is the
-- shape AUDIO already uses one capability over: the metering unit is a property of the MODEL.
--
-- ⚠ 1.0 credits/kilotoken is a PLACEHOLDER matching the CHAT anchor, and wants the same phase-0
-- recalibration as its neighbours. A vision turn's prompt carries the encoded image, so its token
-- count is naturally larger than a text turn's — which is the cost, expressed honestly.
(1, 'IMAGE', 'llava:latest',           'KILOTOKEN', 1.0000,  1,   3),
(1, 'IMAGE', 'llava:v1.6',             'KILOTOKEN', 1.0000,  1,   3),
(1, 'IMAGE', 'bakllava:latest',        'KILOTOKEN', 1.0000,  1,   3),
(1, 'IMAGE', 'llama3.2-vision:latest', 'KILOTOKEN', 1.0000,  1,   3),
-- Composition, priced apart from generation (ADR-041). Both are VIDEO output, but the default
-- VIDEO row above is a DIFFUSION rate: 90 credits per output second is what it costs to invent
-- pixels, and an ffmpeg concat of photos the user already supplied invents none. Left on the
-- VIDEO capability with its own model key rather than given a capability of its own, because the
-- pricebook's (capability, model) key exists for exactly this — the same capability produced by
-- two engines that cost different amounts.
--
-- 'orazaka-compose' is the name the media worker reports for its assembly branch (consumer.py,
-- COMPOSE_ENGINE); pinnedRate prefers a model-specific row and falls back to the capability
-- default, so a generation job is unaffected by this line.
--
-- ⚠ 2.0 credits/second is a PLACEHOLDER on the same footing as its neighbours above and wants the
-- same phase-0 recalibration. It is anchored on the one thing known to be true: an assembly is
-- I/O and codec work of the order of the IMAGE rate per second of output, not of the VIDEO rate.
(1, 'VIDEO', 'orazaka-compose',      'OUTPUT_SECOND', 2.0000,  2,  30),
-- AUDIO is priced per MODEL and has no capability default, because its two halves are
-- metered in different units and are already two catalogues in the config plane: TTS
-- (orazaka_models category 'speech') bills KILOCHAR, STT (category 'audio') bills
-- AUDIO_MINUTE. The metering unit is a property of the model, so it belongs to the row and
-- not to the key — putting `unit` in the unique index would allow a row that cannot exist
-- and would leave a bare (AUDIO, NULL) lookup ambiguous. An unpriced model resolves to no
-- row: logged under DRY_RUN, refused under ENFORCING. Never guess a price for compute.
(1, 'AUDIO', 'piper-en-low',         'KILOCHAR',     4.0000,  4,   4),
(1, 'AUDIO', 'piper-en-medium-ryan', 'KILOCHAR',     4.0000,  4,   4),
(1, 'AUDIO', 'piper-fr-medium',      'KILOCHAR',     4.0000,  4,   4),
(1, 'AUDIO', 'tts-1',                'KILOCHAR',     4.0000,  4,   4),
(1, 'AUDIO', 'whisper-base',         'AUDIO_MINUTE', 6.0000,  6,   6),
(1, 'AUDIO', 'whisper-tiny-en',      'AUDIO_MINUTE', 6.0000,  6,   6)
ON CONFLICT DO NOTHING;

-- ── v1 is history. Retired, never deleted (ADR-047) ──────────────────────────
-- A settled hold names the pricebook version it was priced at, so an audit that cannot resolve
-- v1 cannot verify a debit v1 produced. A fresh database keeps these rows for one reason: to be
-- indistinguishable from a database that lived through the rescale. Two shapes of the same
-- system diverging quietly is how the last four defects in this repository were born.
UPDATE credit_pricebook SET effective_to = now() WHERE version = 1 AND effective_to IS NULL;

-- ── v2: the credit is ten times finer (ADR-047) ──────────────────────────────
-- Every RATE x10 and every FLOOR unchanged, which is the entire decision: the floor was 1 old
-- credit and is now a tenth of one, so a 363-token turn stops costing what a 999-token turn
-- costs. Measured before the change: 37 of 37 metered steps sat under the floor, which charged
-- 1.95x the rate and made 54% of every credit billed padding rather than work (ADR-046 §3).
--
-- The price in euros is unmoved because the money anchor moved with the unit: 1 kilotoken was
-- 1 old credit at 1000 millicents, and is 10 new credits at 100 millicents. Same 1000.
INSERT INTO credit_pricebook (version, capability, model_name, unit, credits_per_unit, minimum_credits, estimate_credits) VALUES
(2, 'CHAT',  NULL, 'KILOTOKEN',       10.0000,  1,   20),
(2, 'IMAGE', NULL, 'IMAGE_STEP',      19.0730,  5,  200),
(2, 'VIDEO', NULL, 'OUTPUT_SECOND',  900.0000, 90, 3600),
(2, 'AGENT', NULL, 'CALL',            50.0000,  5,   50),
(2, 'IMAGE', 'llava:latest',           'KILOTOKEN', 10.0000, 1,  30),
(2, 'IMAGE', 'llava:v1.6',             'KILOTOKEN', 10.0000, 1,  30),
(2, 'IMAGE', 'bakllava:latest',        'KILOTOKEN', 10.0000, 1,  30),
(2, 'IMAGE', 'llama3.2-vision:latest', 'KILOTOKEN', 10.0000, 1,  30),
(2, 'VIDEO', 'orazaka-compose',        'OUTPUT_SECOND', 20.0000, 2, 300),
(2, 'AUDIO', 'piper-en-low',         'KILOCHAR',     40.0000, 4,  40),
(2, 'AUDIO', 'piper-en-medium-ryan', 'KILOCHAR',     40.0000, 4,  40),
(2, 'AUDIO', 'piper-fr-medium',      'KILOCHAR',     40.0000, 4,  40),
(2, 'AUDIO', 'tts-1',                'KILOCHAR',     40.0000, 4,  40),
(2, 'AUDIO', 'whisper-base',         'AUDIO_MINUTE', 60.0000, 6,  60),
(2, 'AUDIO', 'whisper-tiny-en',      'AUDIO_MINUTE', 60.0000, 6,  60),
-- Video ANALYSIS, priced apart from video GENERATION (ADR-066). Same capability, and VIDEO's
-- default is a diffusion rate: 900 credits per output second is what it costs to invent pixels,
-- and reading a file invents none. The quantity is the SOURCE duration — what keyframe extraction
-- and transcription both scale with — and `orazaka-video-analysis` is the engine name
-- VideoAnalysisStrategy reports, the same mechanism `orazaka-compose` uses.
--
-- ⚠ 45 credits/minute is a PLACEHOLDER and NEEDS THE OWNER'S DECISION. It sits below the two
-- whisper rows (60/min) only because this path does the transcription AND the keyframe extraction
-- on one pass of the same hardware; the real number wants a measured run, like every other rate
-- flagged in this file.
(2, 'VIDEO', 'orazaka-video-analysis', 'AUDIO_MINUTE', 45.0000, 5, 90)
ON CONFLICT DO NOTHING;



