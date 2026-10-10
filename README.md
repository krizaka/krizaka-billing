<!-- krizaka-header -->
<div align="center">

<img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/krizaka.svg" alt="Krizaka" width="72">

# Krizaka Billing

**Credits that are never charged twice.**

Wallets, plans, subscriptions, a versioned price book and metering — hold, settle, release — as a service with its
contract and a typed client, so every other service bills through one ledger.

[![CI](https://github.com/krizaka/krizaka-billing/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/krizaka-billing/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.krizaka/krizaka-billing-api?color=3b82f6&label=maven%20central)](https://central.sonatype.com/namespace/com.krizaka)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

[Open source at Krizaka](https://www.krizaka.com/en/open-source) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

Built for and used by [Orazaka](https://github.com/krizaka/orazaka); usable by any Spring Boot application.

## What it does

| Capability | Endpoint |
|:---|:---|
| Plans and subscriptions | `/api/v1/billing/plans` · `/api/v1/billing/subscriptions` |
| Wallets, entitlements, adjustments | `/api/v1/billing/wallets` |
| Price book (versioned, preview, estimate) | `/api/v1/billing/pricebook` |
| Add-on packs and their subscriptions | `/api/v1/billing/packs` · `/api/v1/billing/pack-subscriptions` |
| Usage analytics | `/api/v1/billing/usage` |
| Metering for other services: hold → settle → release — `SERVICE` only | `/internal/v1/billing/credits` |
| Entitlement check — `SERVICE` only | `/internal/v1/billing/entitlements/{actorId}` |

A caller **holds** credits before expensive work, then **settles** the actual cost or **releases** the hold; holds that
are never settled expire. The ledger is append-only and every debit carries an idempotency key, on top of message
deduplication: a redelivered settlement is processed once. Billing events leave through a transactional outbox
(relayed by `krizaka-messaging`); it also implements `OutboxStore.append`, so an `EventPublisher` can write to it with
its `messageId` and envelope headers (`billing_outbox.headers jsonb`), published as stored.

## Events

Billing publishes through its outbox on the platform's events exchange (`krizaka.messaging.exchanges.events`) and
consumes `evt.turn.unmetered`; the body is the bare event, the envelope travels in the `kz-*` AMQP headers. Each event has
a JSON Schema (draft 2020-12) in `krizaka-billing-api`, at `events/<routing-key>.v1.json`:
`evt.turn.unmetered`, `evt.credit.granted`, `evt.usage.recorded`, `evt.wallet.low-balance`,
`evt.subscription.changed`, `evt.subscription.canceled`, `evt.subscription.renewed`, `evt.pack.subscribed`,
`evt.pack.canceled`. A consumer checks its own copy against them with `krizaka-test-support`'s `EventContractTest`.

## Modules

| Artifact | Published | Role |
|:---|:--:|:---|
| `com.krizaka:krizaka-billing-api` | ✓ | The contract: DTOs and the ports (`CreditAuthorizationClient`, `EntitlementProvider`, …). |
| `com.krizaka:krizaka-billing-client` | ✓ | The ports over HTTP with a `SERVICE` token; a no-op adapter when billing is disabled, so no call site branches. Spring Boot auto-configuration. |
| `krizaka-billing-service` | — | The Spring Boot host (port `8095`). Built from source. |
| `infra/initdb/70-billing.sql` | — | Schema, role and catalogue seed of the billing database. |

## Bill from another service

```xml
<dependency>
    <groupId>com.krizaka</groupId>
    <artifactId>krizaka-billing-client</artifactId>
    <version>0.1.0</version>
</dependency>
```

```yaml
krizaka:
  billing:
    enabled: true                         # false wires the no-op adapter
    base-url: http://billing:8095
    service-secret: ${IDENTITY_JWT_SECRET} # the shared HS256 secret
    connect-timeout: 500ms
    read-timeout: 2s
```

## Run the service

```bash
./mvnw -pl krizaka-billing-service -am spring-boot:run
```

| Property | Environment | Default |
|:---|:---|:---|
| `krizaka.billing-service.datasource.url` | `BILLING_DB_URL` | `jdbc:postgresql://localhost:5432/krizaka_billing_db` |
| `krizaka.billing-service.datasource.username` / `.password` | `BILLING_DB_USERNAME` / `BILLING_DB_PASSWORD` | `krizaka_billing` / — |
| `krizaka.billing-service.sweeper.interval` | `BILLING_SWEEPER_INTERVAL` | `60000` ms |
| `krizaka.security.jwt.secret` | `IDENTITY_JWT_SECRET` | — (≥ 32 characters) |
| `server.port` | `BILLING_PORT` | `8095` |

PostgreSQL and RabbitMQ are required; the database, its role (created without a password — set it at deployment) and
the catalogue come from [`infra/initdb/70-billing.sql`](infra/initdb/70-billing.sql). Prices and the hold TTL are rows,
not configuration.

> **Messaging.** Billing listens to job outcomes and publishes its events on the exchanges of the platform it runs on:
> `krizaka.messaging.exchanges.events` / `.dead-letter` (`EVENTS_EXCHANGE` / `DLX_EXCHANGE`, defaults `krizaka.events` /
> `krizaka.dlx`). It owns the queues `krizaka.billing.settlements` and `krizaka.billing.unmetered`.

## Build

```bash
./mvnw verify                        # unit and Testcontainers integration tests (Docker required)
./mvnw verify -Prelease -Dgpg.skip   # + the sources and javadoc jars Maven Central requires
```

It inherits [`krizaka-parent`](https://github.com/krizaka/krizaka-build) and uses
[`krizaka-platform-kit`](https://github.com/krizaka/krizaka-platform-kit): build those first, or let CI do it. JDK 21.

## Contributing

Issues and pull requests are welcome — see the organisation's
[contributing guide](https://github.com/krizaka/.github/blob/main/CONTRIBUTING.md) and
[security policy](https://github.com/krizaka/.github/blob/main/SECURITY.md).

## License

[Apache License 2.0](LICENSE) © 2026 Krizaka
