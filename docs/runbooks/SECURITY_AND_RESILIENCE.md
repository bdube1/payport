# Security and resilience runbook

This document covers requirement 5 (secrets, key rotation, privileged access, tamper-evident audit, DR testing, incident response, tested restores, HSM and PCI DSS). Code gives you the controls; this runbook is the operating procedure an RBZ inspection or a PCI QSA will ask to see evidence of. Assign an owner to each section.

## 1. Secrets management

- **Where secrets live.** All secrets come from environment variables (see `.env.example`); none are in the repo. In production, inject them from a secrets manager such as HashiCorp Vault, AWS Secrets Manager or Azure Key Vault. Do not use a `.env` file on disk.
- **Access.** Only the deployment pipeline identity and two named break-glass administrators can read production secrets. Reads are logged.
- **Inventory.** Keep a register of every secret: owner, where it is used, rotation interval, and last rotated date. This includes DB credentials, the JWT secret, provider credentials (EcoCash, OneMoney, O'mari, ZimSwitch, e-Solutions, Econet/NetOne/Telecel), Africa's Talking, SMTP, WhatsApp, the USSD callback token, partner client secrets (stored encrypted), and data keys.
- **Rotation script.** `scripts/rotate-secrets.sh` exists for the gateway's secrets; extend it with the PSP ones.

| Secret | Rotate | How |
|---|---|---|
| DB password | 90 days | Create a new user/password, deploy, drop the old one |
| JWT secret | 180 days or on suspicion | Deploy the new secret; every session is forced to sign in again |
| Provider credentials | Per provider policy / on staff exit | Through each provider's portal |
| Partner HMAC secrets | Yearly or on request | `POST /api/v1/psp/admin/channels/partners/{keyId}/rotate-secret` |
| USSD token, WhatsApp app secret | Yearly | Update at the aggregator/Meta, then the env |
| Data encryption keys | Yearly (see §2) | Key rotation procedure |

## 2. Encryption keys and HSM

- **Field encryption.** AES-256-GCM, with each value tagged by its key id (`enc:v1:<keyId>:...`). It encrypts payout account numbers, mobile numbers and partner secrets. Lookups use a separate HMAC blind-index key.
- **Rotating the data key.**
  1. Generate a new 32-byte key in the KMS/HSM.
  2. Add it to `PAYPORT_DATA_KEYS` and keep the old one there.
  3. Set `PAYPORT_DATA_KEY_ACTIVE` to the new id and deploy. New writes use the new key; old values still decrypt.
  4. Re-encrypt old rows: load and save each payout account and partner client, which rewrites them under the active key. Then confirm no value still carries the old key id with `SELECT COUNT(*) ... LIKE 'enc:v1:<old>:%'`.
  5. Remove the old id. Record the rotation in the key register.
- **Index key.** The blind-index key is *not* rotated routinely. Rotating it means recomputing every index.
- **HSM.** Crypto goes through the `KeyProvider` interface. For an HSM (Thales Luna, Utimaco) or a cloud KMS, implement `KeyProvider` as an `@Primary` bean. Use envelope encryption: the HSM holds the key-encryption key and unwraps data keys at startup, so keys never exist in plaintext config. If you take card PINs or issue cards, PIN processing must happen in a payment HSM; that is out of scope for this codebase.

## 3. Privileged access

- **Segregation of duties.** Money-affecting changes need a second person: settlement approval, ledger adjustments and reversals, fee and tax changes, payout account verification, merchant reinstatement, payment resolution, FX rates, float, and cutover. The approver must differ from the requester; the application enforces this, and the database does too (`chk_four_eyes`).
- **Role hygiene.** No single user should hold both the requester and the approver role for the same action. ADMIN holds every role, so issue it to no more than two people and review its use monthly.
- **Production access.**
  - No standing database or SSH access.
  - Access is just-in-time through a bastion with session recording, approved per ticket.
  - The DB user the app connects as must not have SUPER or TRIGGER privileges in production, so it cannot drop the append-only triggers.
- **Reviews.**
  - Quarterly access review of staff users, roles and partner keys, signed off by compliance.
  - Leavers are disabled the same day.

## 4. Tamper-evident audit

- **What is recorded.** Every PSP state change writes to `psp_audit_events`, which is append-only (enforced by a trigger). Events are sealed in a hash chain, and the ledger entries form a second chain.
- **Verification.** Check a chain at any time with `GET /api/v1/psp/admin/platform/chains/{AUDIT|LEDGER}/verify`. The nightly integrity job also verifies both chains and that balances equal the sums of lines. The latest result is at `/platform/integrity/last`.
- **External anchor.** Every day, ship the latest chain seal (head hash and sequence) to a store PayPort admins cannot rewrite, such as WORM object storage or your SIEM. This proves the chain was not rebuilt end to end.
- **Integrity failure.** A failed integrity check is a **P1 incident** (see §7).

## 5. Monitoring and SIEM

Ship application logs and audit events to a SIEM. Alert on:
- a failed integrity job, or any break type `LEDGER_INTEGRITY`;
- outbox events in DEAD state, or outbox lag above 5 minutes;
- unresolved payments older than 30 minutes (count above your threshold);
- spikes in signed-request rejections (bad signature, replayed nonce, IP not allowed);
- failed staff logins, PIN lockouts and approval rejections;
- recon breaks above a value threshold, and the safeguarding report showing customer funds above trust balances.

## 6. Backups, restore tests and disaster recovery

| | Target |
|---|---|
| RPO (data loss) | ≤ 5 minutes: binlog/PITR shipping to a second region |
| RTO (downtime) | ≤ 4 hours |
| Full backup | Daily, encrypted, kept 35 days; monthly kept 7 years (financial records) |

**Quarterly restore test**, recorded with evidence:
1. Restore the latest backup plus binlogs to an isolated environment, at a chosen point in time.
2. Start the app against it with `PSP_SANDBOX=true`, the providers pointed at sandboxes, and the schedulers off.
3. Run `POST /platform/integrity/run`. It must pass.
4. Compare the trial balance with production as of the same point in time.
5. Record how long the restore took (this is your measured RTO) and file it.

**Twice-yearly DR exercise.** Fail over to the secondary site, process test transactions end to end, then fail back.

Because the callback inbox persists before acknowledging and the resolver re-queries providers, nothing is lost if the app dies mid-payment. After a failover, the resolver picks up uncertain payments and the fulfilment sweeper re-queues paid but unfulfilled ones.

## 7. Incident response

**Severity levels:**
- **P1:** funds at risk, ledger integrity failure, data breach, or provider credentials exposed.
- **P2:** a rail is down, settlement is delayed, or a recon break is above threshold.
- **P3:** everything else.

**P1 procedure:**
1. Incident commander declared; open a war-room channel and timeline.
2. Contain:
   - suspend the affected merchants or agents (`/merchants/{id}/suspend`, agent suspend);
   - freeze wallets;
   - revoke partner keys;
   - disable routing rules for a compromised rail;
   - rotate any exposed secrets (§1).
3. Preserve evidence: snapshot the DB and logs, and export the audit chain and seals.
4. Assess: run integrity checks, and pull ledger statements for the affected accounts.
5. Notify:
   - RBZ National Payment Systems as your licence conditions require;
   - POTRAZ under the Cyber and Data Protection Act if personal data was breached, within its notification deadline;
   - affected merchants and customers;
   - the FIU if fraud or money laundering is suspected.

   Confirm the current deadlines with your legal adviser and keep them in this document.
6. Recover: fix the root cause. Correct any money only through four-eyes ledger adjustments, never by editing data directly.
7. Post-incident review within 5 business days; track the actions to closure.

Keep a contact sheet next to this runbook: provider NOCs, the sponsor bank, RBZ, the FIU, the CDPA contact, and your on-call rota.

## 8. PCI DSS scope

- **Today.** PayPort's card acceptance is through ZimSwitch, using the provider's hosted widget. Card numbers never reach PayPort servers, which keeps you in the smallest scope (SAQ A, or SAQ A-EP if your page loads the widget script). Keep it that way.
- **Never in code or logs.** PANs, CVVs and track data must never appear in request logs or the database. Any new card integration must use hosted fields, iframes or tokenisation.
- **Becoming an acquirer or processor.** Hosting card entry or connecting directly to schemes means full PCI DSS v4.0 Level 1: a QSA assessment, quarterly ASV scans, a yearly penetration test, network segmentation, file integrity monitoring and a payment HSM. Budget for it before signing such a contract.
- **Applies regardless of card scope** (these are good practice and expected by RBZ):
  - TLS 1.2+ everywhere;
  - a WAF in front of the API;
  - vulnerability scanning;
  - a yearly penetration test;
  - secure SDLC: code review, dependency scanning, no secrets in the repo.

## 9. Edge

The app expects to sit behind a reverse proxy or API gateway that:
- terminates TLS and, for partners and POS, mTLS. It forwards the verified client certificate in `X-Client-Cert`, and only from the IPs listed in `PSP_TRUSTED_PROXIES`;
- runs a WAF (OWASP core rule set) and per-IP rate limits;
- exposes only `/api/**` and the consumer and merchant front ends. Actuator and admin paths are reachable only from the office VPN.
