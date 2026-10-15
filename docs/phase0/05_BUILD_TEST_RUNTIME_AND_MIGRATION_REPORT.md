# Phase 0: Build, test, runtime and migration report

Date: 2026-10-15. Environment: isolated Linux cloud workspace, OpenJDK 21, Maven 3, MySQL 8.0.46 (installed from the Ubuntu archive for this assessment).

Evidence labels used throughout Phase 0:
- **[Tested]**: executed here, result observed.
- **[Observed]**: read in source or configuration, not executed.
- **[Not verified]**: could not be checked; the reason is given.

## 1. Resources inspected

| Resource | Available | Notes |
|---|---|---|
| Existing gateway source (`payport-gateway.rar` you uploaded) | Yes | Extracted to an isolated directory. 222 Java files, 25,940 lines, 31 Flyway migrations, 3 test classes. |
| `psp-foundation` branch | Yes | Same repository, branch commit `71a70c9` (one commit on top of the gateway import `9f7d3a9`). |
| `payport-psp.zip` | Yes | Packaging of the branch above, so no separate inspection is needed. |
| Frontend applications (consumer app, merchant dashboard, admin UI) | **No** | Not in the archive. The config points to `app.payport.africa` and a website base URL. **Needed:** the frontend repositories. |
| Live deployment (servers, `.env`, reverse proxy, DB) | **No** | Only `start.sh`, `docker-compose.yml`, `Dockerfile` and `application.properties` are available. **Needed:** the deployment description, or read access to a non-production copy. |
| Production database or anonymised copy | **No** | **Needed:** an anonymised dump to test the upgrade on real data. |
| Provider specifications and sandbox credentials | **No** | **Needed:** per provider (see the integration inventory). |
| Architecture diagram | Yes | The PSP reference diagram from your first message. |
| Git history of the gateway | **No** | The archive was imported as one commit, so the change history is unknown. |

## 2. Build

| Step | Result | Evidence |
|---|---|---|
| `mvn -B -DskipTests package` on the branch | **Failed: dependencies unavailable** [Tested] | `Non-resolvable parent POM ... spring-boot-starter-parent:pom:3.2.0 ... Could not transfer artifact ... from/to central`. The session's egress policy refuses Maven Central (HTTP 403 from the proxy); this is a policy limit, not a fault in the project. The local `~/.m2/settings.xml` also pointed at a stale proxy port. |
| Compilation of the 225 PSP files and 11 edited gateway files | Compiled [Tested], **but against hand-written stub libraries, not the real Spring, Hibernate and Jackson jars** | This is **not** evidence that the application builds. The stubs prove only that names and signatures used by the PSP code are consistent with each other and with gateway signatures. They cannot catch annotation-processing errors, Lombok behaviour, Spring Data query derivation errors, or behaviour of the real libraries. |
| Real build of the original gateway | **Not verified** | Same dependency block. |

**Consequence:** under section 16 of your engineering prompt, no claim that "the application builds" is supported yet. **Every PSP feature is at most "implemented but not verified".**

**Needed to close:** one of the following.
- (a) Allow this environment to reach Maven Central.
- (b) Run `mvn -B verify` in your CI or on a developer machine and share the full log.
- (c) Provide an internal Maven mirror.

## 3. Tests

| Suite | Result | Notes |
|---|---|---|
| Gateway's 3 test classes (JWT device check, NetOne message, customer message translator) | **Not run** | Need the real JUnit/Spring test dependencies. |
| PSP domain tests (33 cases: ledger posting rules, fee maths, risk rule evaluation, request signing, polling policy, EMV QR CRC) | 33 passed, run through a hand-written JUnit-compatible shim [Tested, weak] | These are pure-logic unit tests. They do not touch Spring, the database, HTTP or providers. **They are not integration, contract, concurrency or failure-recovery tests.** |
| Integration, contract, concurrency and end-to-end tests | **None exist** | No Testcontainers or simulator-based flows. |

## 4. Runtime

| Check | Result |
|---|---|
| Application started | **No**: needs the build. |
| Representative payment flow exercised | **No** |
| Hibernate `validate` of all entities against the migrated schema | **No**: needs the runtime. A Python cross-check of the generated entity specs against the DDL reports 0 mismatches, but that check shares its source of truth with the generator, so it is weak evidence. |

## 5. Database migrations: tested on a real MySQL 8.0.46

Setup: binary logging **on** (as in production with replication or PITR), `STRICT_TRANS_TABLES`. Migrations ran as an application user with `ALL` on the schema and no `SUPER`, which is the realistic production grant.

| Test | Result |
|---|---|
| Original gateway V1–V31 on an empty database | **Pass** [Tested]: 0 errors, 32 tables. |
| Upgrade: V32–V40 applied on top of V1–V31 with default server settings | **FAIL** [Tested]: V32 at line 111 and V33 at line 102 raise `ERROR 1419: You do not have the SUPER privilege and binary logging is enabled`. Tables before the triggers were created, so **under Flyway this leaves a half-applied, failed migration**. V34–V40 applied. |
| Upgrade with `log_bin_trust_function_creators=1` set by the DBA | **Pass** [Tested]: 0 errors. |
| Clean V1–V40 with that setting | **Pass** [Tested]: 80 tables, 8 triggers. Seeds present: 14 routing rules, 8 risk rules, 16 templates, 6 KYC tier rows, 12 tariffs. |
| Upgraded schema vs clean schema | **Identical** [Tested]: column-by-column diff of `information_schema.columns`. |

### Database-level invariant tests (on the migrated schema)

| Invariant | Result |
|---|---|
| Duplicate journal idempotency key rejected | **Enforced** (unique key) |
| Zero or negative journal line amount rejected | **Enforced** (CHECK) |
| UPDATE of a journal entry rejected | **Enforced** (trigger: "append-only: post a reversal or adjustment") |
| DELETE of a journal line rejected | **Enforced** (trigger) |
| Requester approving their own request rejected | **Enforced** (`chk_four_eyes`) |
| Different approver accepted | Accepted (correct) |
| `settlements.status = 'MIGRATED_TO_LEDGER'` permitted | Permitted (constraint widened correctly) |
| **Unbalanced journal (extra debit line) rejected** | **NOT enforced**: the database accepted it. Balance is enforced only in application code, and detected after the fact by the nightly integrity job. |
| **`TRUNCATE psp_journal_lines` by the app user** | **Succeeded**: TRUNCATE bypasses row triggers. **Append-only is defeated.** |
| **`DROP TRIGGER` by the app user** | **Succeeded**: whoever holds the app's DB credentials can remove the protection. |
| Non-Zimbabwe currency (`KES`) in the ledger | Rejected by `chk_ledger_acct_ccy IN ('USD','ZWG')`: **blocks multi-country by design**. |

### Required fixes, proposed for Phase 1
1. Split the trigger DDL into a DBA-applied migration, or document and automate `log_bin_trust_function_creators`. Make Flyway fail fast before creating any table if the precondition is missing.
2. Run the application with a least-privilege DB user: no `DROP`, `ALTER`, `TRIGGER`, `CREATE` or `TRUNCATE`. Migrations run under a separate migration user.
3. Add a deferred balance check: a per-entry balance verification inside the posting transaction (already in code) **plus** a database-side check on commit, using an entry-level `total_debits = total_credits` column checked on insert of the last line. Alternatively, accept application-only enforcement with continuous detection. This is an ADR decision (ADR-0004).
4. Replace the currency CHECK with a reference table of enabled currencies (ADR-0006).

## 6. Deployment configuration [Observed]

- `start.sh` runs a JAR from `/opt/payport/gateway/target` with `.env` sourced. **This appears to be the real deployment method** (bare VM, single instance) [Assumed].
- `Dockerfile` copies `gateway-core/target/gateway-core-*.jar`, but the artifact is `payport-gateway` at the repository root. **The Docker build cannot work as written.**
- `docker-compose.yml`:
  - publishes MySQL `3306` and Redis `6379` on host ports;
  - sets `useSSL=false` on the JDBC URL;
  - provisions Redis, but nothing in the code uses it;
  - its health check uses `curl`, which the `temurin:21-jre-alpine` base image does not include.
- `spring.jpa.hibernate.ddl-auto=update` alongside Flyway: Hibernate can silently alter production schema.
- `spring.main.allow-bean-definition-overriding=true`: a bean-name collision silently replaces a bean instead of failing at startup.
- **10 `@Scheduled` jobs, with no distributed lock** (no ShedLock or `SKIP LOCKED` claim). Running two application instances would run every job twice. The PSP outbox uses a claim-by-UPDATE pattern; the other PSP schedulers would also need locks. **The system is single-instance today** [Observed].
- Spring Boot **3.2.0**: this release line is out of open-source support. Upgrading is a Phase 1 prerequisite [Assumed from release policy; confirm the date].
