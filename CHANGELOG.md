# Changelog

All notable changes to this repository are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).
Every Krizaka JVM artifact is released at the same version.

## [Unreleased]

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
