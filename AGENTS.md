# krizaka-billing — Scope (agent-neutral)

> A Krizaka building block: credits, wallets, plans, subscriptions, a price book and metering, published on Maven
> Central as `com.krizaka:krizaka-billing-{api,client}`. Orazaka is its first consumer, not its owner. When this
> repository is cloned inside the Orazaka workspace (`krizaka/krizaka-billing`), the workspace contract
> ([`krizaka/orazaka/AGENTS.md`](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)) applies as well, and its
> cross-repository rules scan this repository.

## Rules of this repository

- **Depends on Krizaka artifacts only** (`krizaka-build`, `krizaka-platform-kit`) — never on a product;
  `dependsOnNoProduct` fails the build.
- **Money is append-only.** The ledger is written once and corrected by adjustments, never by updates; a hold is
  settled or released exactly once, and two independent guards (message dedup, ledger idempotency key) stand between a
  redelivery and a double debit.
- **Other services use the contract or the client** — never the service's implementation.
- **Owns its schema**: `infra/initdb/70-billing.sql`, read by the integration tests through `InitDb.locate`.
- **Configuration**: `krizaka.billing.*` (client) and `krizaka.billing-service.*` (service).
- **Services ship as Docker images, never on Maven Central.** Only the libraries (`-api`, `-client`, …) are published; a
  `*-service` host sets `maven.deploy.skip` and is listed in the root POM's `central-publishing-maven-plugin`
  `excludeArtifacts` (the plugin stages every module of the reactor otherwise). The `publishable-artifact-size` enforcer
  rule fails `verify` when a published jar exceeds 5 MB — a runnable (fat) jar never reaches Central.

## Definition of done

1. `./mvnw verify -Prelease -Dgpg.skip` is green (unit, Testcontainers integration tests, governance, javadoc).
2. Inside the Orazaka workspace, `./mvnw install` from the root is green.
3. [README.md](README.md) and [CHANGELOG.md](CHANGELOG.md) describe the change.
