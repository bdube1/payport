# Pilot security review and PCI DSS self-assessment (2 Nov 2026)

What was checked in the code and configuration, what was fixed, and what has to be done outside the code before
and during the pilot. This is a self-assessment: PCI DSS compliance is confirmed only by a Qualified Security
Assessor (QSA) or the self-assessment questionnaire the acquirer accepts, with quarterly scans by an Approved
Scanning Vendor (ASV) and an independent penetration test.

## Fixed in this review

| # | Finding | Risk | Fix |
|---|---|---|---|
| 1 | The rate limiter believed the caller's own `X-Forwarded-For` header | Anyone could send a different made-up address on each request and escape every limit: password guessing on staff, merchant and agent sign-in, and look-ups of customer names | Only our own proxy's entry is believed (RateLimitFilter.clientIp), tested |
| 2 | Look-ups by code were counted per exact address | `/merchants/by-code/000001`, `/000002`, ... each had its own allowance, so codes could be listed at full speed | One allowance per kind of look-up (bucketOf), tested |
| 3 | O'mari codes and tap tokens had no per-address limit | Guessing at scale | Added to the limited calls (each payment also allows only 3 code tries; tap tokens are 192-bit random, one use, 90 s) |
| 4 | The O'mari adapter wrote the customer's one-time code and the first 20 characters of the O'mari key to the log | Secrets in logs (PCI DSS 3.x / 10.x) | Removed |
| 5 | The portals (ops, merchant, customer website and hosted card checkout) sent no browser security headers | Clickjacking of the ops portal and the checkout, downgrade to http, type sniffing; a card checkout page without a content security policy (PCI DSS 6.4.3, 11.6.1) | HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy, and a content security policy (report-only for the first pilot week, then enforced) |
| 6 | Two RestTemplate beans; which one won depended on load order | EcoCash calls could go out without the egress proxy and without timeouts | One bean (earlier PR) |
| 7 | Chat attachments (new) | Malware, scripts and phishing pages uploaded as "documents" | Type decided by content, only safe types, photos re-encoded and moderated, encrypted at rest, one-hour signed links, downloads forced, sandbox CSP |

## Checked and found sound

- **SQL**: queries use bound parameters; the few built strings use fixed column names or numbers only.
- **Access**: customer, merchant, agent and staff routes are role-checked on the server; wallets, statements, chats
  and orders check ownership (another customer's id returns not found).
- **Passwords and PINs**: BCrypt; account lockout after wrong attempts; sign-in again after a change.
- **Tokens**: JWT secret from the environment (no default); one hour access tokens.
- **Money**: the ledger refuses unbalanced entries and overdrafts of protected accounts in the database itself;
  payment windows, one-use claims and idempotency keys stop double payment and double delivery.
- **Provider callbacks** are signature-checked; merchant webhooks are signed.
- **Metrics** (`/actuator/prometheus`) need the scrape token; health only is public.
- **Card data**: Payport never sees card numbers on acceptance (the card form is ZimSwitch/OPPWA's own widget); card
  issuing keeps only masked numbers and the issuer-processor holds full card data.
- **Store pages**: strict content security policy, no inline scripts, uploads re-encoded.

## PCI DSS scope (as built)

| Flow | Card data at Payport? | Likely questionnaire |
|---|---|---|
| Card acceptance on the hosted checkout (ZimSwitch / OPPWA widget) | No, the widget is loaded into Payport's page | SAQ A-EP for the checkout page (the page that loads the widget is in scope) |
| Card issuing (virtual and physical cards) | Card number passes through on "show card details", never stored | Service provider: SAQ D for Service Providers, or a QSA Report on Compliance above 300,000 card transactions a year |
| POS | Through the acquirer's terminal app | Acquirer's scope |

## Still to do (outside the code), in order

1. **Two-step sign-in (MFA) for staff, merchants and agents** (PCI DSS 8.4). Not built yet: the next item to build.
2. **External penetration test** of the pilot environment by an independent firm (scope written in
   docs/security), and **ASV external scan**; repeat quarterly and after major changes.
3. **Servers**: SSH keys only, no root login, unattended security updates, DigitalOcean firewalls as in
   SCALING_DIGITALOCEAN.md, database not public, backups encrypted, restore drill monthly.
4. **Secrets**: move `.env` secrets (database, JWT, provider keys, data keys) to a secret store or at least
   root-only files; rotate the O'mari key (it was shared in a chat) and any key a former staff member saw.
5. **Logs**: keep security logs 12 months (3 months searchable); alert on sign-in failures, rule changes and
   privileged actions (the Security dashboard already shows them).
6. **People**: security awareness for staff, background checks for administrators, written incident response
   plan with the regulator and the sponsor bank's contacts.
7. Switch the portal content security policy from report-only to enforced after the first clean pilot week.

## Load and resilience

`loadtest/` (k6) and `docs/pilot/SCALING_DIGITALOCEAN.md`. Run smoke, load, stress, spike and soak against the
pilot before onboarding pilot users, and after each release.
