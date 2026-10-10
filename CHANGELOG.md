# Changelog

All notable changes to this repository are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).
Every Krizaka JVM artifact is released at the same version.

## [Unreleased]

### Added

- Event contracts: `krizaka-billing-api` publishes the JSON Schema (draft 2020-12) of each billing event —
  `evt.turn.unmetered`, `evt.credit.granted`, `evt.usage.recorded`, `evt.wallet.low-balance`,
  `evt.subscription.changed|canceled|renewed`, `evt.pack.subscribed|canceled` (`events/<routing-key>.v1.json`).
  `BillingEventsContractTest` and `UnmeteredTurnContractTest` check the producers, `SubscriptionEventContractTest` the
  client's own copy (`krizaka-test-support` `EventContractTest`).
- `OutboxService` implements `krizaka-messaging`'s `OutboxStore.append(NewOutboxMessage)`: a row written by an
  `EventPublisher` keeps its `messageId` and envelope headers, and the relay publishes them as AMQP headers
  (`OutboxDrainIT`, real `70-billing.sql`).

### Changed

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
