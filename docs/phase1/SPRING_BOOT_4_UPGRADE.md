# Spring Boot upgrade: 3.2.0 → 3.5.16 → 4.1.1 (Phase 1 exit criterion E6)

_Branch `phase1-spring-boot-upgrade` (on top of `phase1-financial-foundation`). 2026-10-16._

## Why

The application was on Spring Boot **3.2.0**, a line that is no longer supported. The **whole 3.x line is out of open-source support**: 3.5, its last release line, reached end of life on 30 June 2026. The supported line is **4.1** (latest 4.1.1, supported until July 2027). 4.0 reaches end of support on 31 December 2026, so it is not a sensible target.

## How

Following the official migration guide: first the latest 3.x, then the major upgrade. CI had to pass at each step.

| Step | Change | CI |
|---|---|---|
| 1 | 3.2.0 → 3.5.16; Lombok and MySQL driver versions now managed by Spring (pins removed) | All green |
| 2 | 3.5.16 → 4.1.1 | Green after the fixes below |

## Changes for 4.1.1

| Area | Change | Reason |
|---|---|---|
| Web | `spring-boot-starter-web` → `spring-boot-starter-webmvc` | Old starter deprecated in Boot 4 |
| Flyway | `flyway-core` → `spring-boot-starter-flyway` (with `flyway-mysql`) | Boot 4 no longer auto-configures bare Flyway: **migrations would silently not run** |
| JSON (HTTP) | Spring MVC now uses **Jackson 3**. `spring.jackson.use-jackson2-defaults=true` keeps the response shape; `spring.jackson.datatype.datetime.write-dates-as-timestamps=false` keeps ISO date strings | The property moved in Jackson 3, and the old name **stopped the application from starting** (found by the smoke test) |
| JSON (code) | The app's own `ObjectMapper` (AppConfig, used by 25 classes) stays on Jackson 2 | Lower-risk upgrade. **Follow-up:** move it to Jackson 3 |
| Tests | Testcontainers 2 artifacts (`testcontainers-mysql`, `testcontainers-junit-jupiter`) and package (`org.testcontainers.mysql.MySQLContainer`); `TestRestTemplate` from `spring-boot-resttestclient` plus `@AutoConfigureTestRestTemplate` | Boot 4 test modularisation |
| Tests | `commons-lang3` added (test scope) | Testcontainers 2 needs it, and Boot 4 no longer brings it in. Without it Testcontainers **reported "no Docker" and the suites skipped silently** |

## Safeguards added to CI because of this upgrade

1. **Skipped required tests fail the build.** The first 4.1.1 run was green while all 8 application smoke tests and the Flyway migration test had been skipped. `scripts/ci/test-summary.sh` now fails when `ApplicationSmokeTest` or `LivemodeMigrationTest` skip.
2. **`DockerAvailabilityTest`** reports the real reason if Testcontainers cannot use Docker in CI.
3. **Failure root causes** (`Caused by:` lines) are published as annotations.
4. **JSON contract assertion** in the smoke test covers the envelope shape, omitted nulls, and dates that are not arrays.

## Evidence (GitHub Actions run 38816157210)

| Job | Result |
|---|---|
| build-and-test (`mvn verify`, Boot 4.1.1) | **70 passed, 0 failed.** Includes 8 application smoke tests that boot the whole app on MySQL 8, the real-Flyway migration test and the Docker check. The 106 database tests are skipped here by design and run in `ledger-db` |
| ledger-db | **106 / 106 passed** |
| migrations-production-like | **Pass** |

## Before production

- **Frontends:** test the consumer app, merchant dashboard and admin against staging. JSON shape is kept by configuration and asserted by the smoke test, but only the real clients prove it.
- **Spring Security 7, Hibernate 7, Jakarta EE 11:** run a staging soak and watch the logs for deprecation or behaviour changes not covered by tests (e.g. lazy-loading, query semantics).
- **Rollback:** deploy the previous JAR. No schema changes come from the upgrade itself.
