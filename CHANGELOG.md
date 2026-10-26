# Changelog

All notable changes to this repository are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).
Every Krizaka JVM artifact is released at the same version.

## [Unreleased]

### Added

- Price catalogue (krizaka/orazaka#417, ADR-009): `billing_price` (dated, append-only versions, audit in `billing_version_history`), seeded with Orazaka's defaults; `GET /api/v1/billing/prices` (public view: no `internal`, `preparing` without figure) and `/admin` (entries, `POST /admin/{key}/schedule`, `/admin/{key}/history`); packs read their amount through it by key (`orazaka.<packKey>`) and a pack subscription stores its `price_version_id`. Existing databases: `infra/migrations/2026-10-26-price-catalogue.sql`.
- Billable capabilities `EXTERNAL_CHECK` (court records, credit, identity checks) and `RESEARCH` (grounded research,
  ADR-087), both metered per `CALL` with `model_name` = the provider key. Seeded at pricebook v2:
  `(EXTERNAL_CHECK, mock)` 50 credits/call and `(RESEARCH, law-corpus)` 10 credits/call (placeholders, floor 5). An
  existing database: `infra/migrations/2026-10-25-pricebook-external-check-research.sql` (idempotent). The
  `evt.turn.unmetered` schema's `capability` enum lists them; `BillingSchemaCapabilityTest` and
  `UnmeteredTurnContractTest` fail when the enum, the schema comment, the seed or the event schema drift apart.
- `ConsumptionReport` gains `calls` (`Long`, nullable, the **last** component — the wire reads by name, constructors
  by position). Present, it is the `CALL` quantity: `3` bills three calls, `0` or a negative count settles nothing
  (the hold is released). Absent, a `CALL` is still one per terminal outcome. The former 11-argument constructor stays
  (binary compatible, japicmp) and means « no count »; a caller that counts calls uses the 12-argument canonical one
  (orazaka-packs#138).
- `GET /api/v1/billing/usage/me?month=yyyy-MM`: the caller's own usage and cost for a calendar month (credits, events, cost at the money anchor, per capability × model, plan and unspent grant). The actor is the token's subject, so a product calling with its own API key reads its own usage and no other.

- Event contracts: `krizaka-billing-api` publishes the JSON Schema (draft 2020-12) of each billing event —
  `evt.turn.unmetered`, `evt.credit.granted`, `evt.usage.recorded`, `evt.wallet.low-balance`,
  `evt.subscription.changed|canceled|renewed`, `evt.pack.subscribed|canceled` (`events/<routing-key>.v1.json`).
  `BillingEventsContractTest` and `UnmeteredTurnContractTest` check the producers, `SubscriptionEventContractTest` the
  client's own copy (`krizaka-test-support` `EventContractTest`).
- `OutboxService` implements `krizaka-messaging`'s `OutboxStore.append(NewOutboxMessage)`: a row written by an
  `EventPublisher` keeps its `messageId` and envelope headers, and the relay publishes them as AMQP headers
  (`OutboxDrainIT`, real `70-billing.sql`).

### Changed

- **Canadian dollars by default.** A new runtime row `billing.currency` (ISO 4217, default `CAD`) is the currency of every
  amount stated without one; `BillingConfigurationService.currency()` reads it. A plan saved without a currency takes it
  (`PlanCatalogService`) instead of the former hard-coded `EUR`, and a code that is not ISO 4217 is refused. The seeded
  plans are Free 0 · Premium 29 · Ultimate 79 **CAD** (were 0 · 19 · 49 EUR), and `billing_plan.currency` has no column
  default anymore. An existing database: `INSERT INTO billing_runtime_config (config_key, config_value, value_type)
  VALUES ('billing.currency', 'CAD', 'string') ON CONFLICT DO NOTHING;` then re-price its plans (the Orazaka workspace
  ships `infra/migrations/2026-10-11-billing-currency-cad.sql`).
- Built on `krizaka-parent` and `krizaka-platform-kit` **0.2.0** (released on Maven Central); this repository's
  version follows the parent (0.2.0, not yet released).
- The service host is never published to Maven Central: it is excluded from the Central bundle by name
  (`excludeArtifacts`) and ships as a Docker image; the `publishable-artifact-size` enforcer rule fails `verify` when a
  published jar exceeds 5 MB.
- `billing_outbox` gains `headers JSONB NOT NULL DEFAULT '{}'` (`infra/initdb/70-billing.sql`). An existing database
  needs `ALTER TABLE billing_outbox ADD COLUMN IF NOT EXISTS headers JSONB NOT NULL DEFAULT '{}'::jsonb;`.

## [0.1.0]

First release as a Krizaka building block (formerly `orazaka-billing`, part of the Orazaka platform).

### Changed

- Coordinates `com.krizaka:krizaka-billing-{api,client}` (was `com.orazaka:orazaka-billing-*`), packages
  `com.krizaka.billing.*`; the service host is built from source.
- Configuration: the client under `krizaka.billing.*`, the service under `krizaka.billing-service.*`
  (was `orazaka.billing.*` / `orazaka.billing-service.*`).
- Security chain on `krizaka-security`'s `SecurityBaseline`; service tokens from `krizaka-security`.
- `OutboxService` is the context's `OutboxStore`, relayed by `krizaka-messaging`; settlement messages deduplicated by
  `MessageDedup`.
