<!-- krizaka-header -->
<div align="center">

<img src=".github/assets/orazaka-logo.svg" alt="Orazaka" width="420">

# Orazaka Billing

**The AI that never leaves home.**

Credits, wallets, plans, subscriptions, pricebook and metering (hold → settle → release) as a reusable billing service, with its contract (billing-api) and a typed HTTP client (billing-client).

[![CI](https://github.com/krizaka/orazaka-billing/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-billing/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f59e0b)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-6366f1)](https://www.krizaka.com/en/products/orazaka)

[Documentation](https://www.krizaka.com/en/products/orazaka) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Domain service — reusable by any Krizaka application · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

| Capability | Endpoint |
|:---|:---|
| Plans & subscriptions | `/api/v1/billing/plans` · `/api/v1/billing/subscriptions` |
| Wallets, entitlements, adjustments | `/api/v1/billing/wallets` |
| Pricebook (versioned, preview, estimate) | `/api/v1/billing/pricebook` |
| Packs & pack subscriptions | `/api/v1/billing/packs` · `/api/v1/billing/pack-subscriptions` |
| Usage analytics | `/api/v1/billing/usage` |
| Metering for other services: hold → settle → release | `/internal/v1/billing/credits` |
| Entitlement check | `/internal/v1/billing/entitlements/{actorId}` |

| Module | Role |
|:---|:---|
| `orazaka-billing-api` | Tier-1 contract (DTOs, ports). |
| `orazaka-billing-client` | Typed `RestClient` adapter for other services (no-op adapter when billing is disabled). |
| `orazaka-billing-service` | Spring Boot host (port `8095`), own database `orazaka_billing_db`. |
| `infra/initdb/70-billing.sql` | Schema, role and catalogue seed. |

## Use it

```xml
<dependency>
    <groupId>com.orazaka</groupId>
    <artifactId>orazaka-billing-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Position in the platform

| | |
|:---|:---|
| Depends on | [`orazaka-build`](https://github.com/krizaka/orazaka-build) |
| Used by | [`orazaka-studio`](https://github.com/krizaka/orazaka-studio) · [`orazaka-ai-engine`](https://github.com/krizaka/orazaka-ai-engine) · [`orazaka-conversation-service`](https://github.com/krizaka/orazaka-conversation-service) · [`orazaka-job-service`](https://github.com/krizaka/orazaka-job-service) · [`orazaka-automation-service`](https://github.com/krizaka/orazaka-automation-service) |
| Workspace path | `orazaka-apps/services/orazaka-billing` |

## Build

**Inside the Orazaka workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/orazaka.git && cd orazaka
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-billing/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/orazaka#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
