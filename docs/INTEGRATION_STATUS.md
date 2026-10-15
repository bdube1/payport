# Integration status

> **Superseded by Phase 0 (2026-10-15).** Statuses marked "Built" or "Live" in this document mean *implemented but not verified*: the code was never built with its real dependencies or run. See `docs/phase0/01_CAPABILITY_REGISTER.md` and `docs/phase0/02_RISK_REGISTER.md`. Do not deploy the `psp-foundation` branch as a unit (ADR-0002).


**Live** means the gateway already has a working adaptor. **SPI ready** means the PSP has the interface and a manual or file path, plus a sandbox simulator for testing. The provider's real API still has to be wired once you have credentials and their specification.

| Integration | Purpose | Status | Production path today |
|---|---|---|---|
| EcoCash | Collections | **Live** (gateway adaptor) + verify-by-query | — |
| OneMoney | Collections | **Live** + verify-by-query | — |
| O'mari | Collections | **Live** | — |
| InnBucks | Collections | Not built | Add a gateway payment adaptor, then a routing rule and merchant product |
| ZimSwitch | Card / interbank collections | **Live** | — |
| Telecash | Collections | **Routing rule disabled**; verify-by-query wired | Enable the rule once the collection adaptor is live |
| Econet / NetOne / Telecel | Airtime, data | **Live** | — |
| e-Solutions | ZETDC (ZESA tokens), Bulawayo City Council, ZOL, Nyaradzo, Moonlight | **Live** | — |
| Other councils, ZINARA, DStv, TelOne | Bills | Not built | Add as gateway fulfilment adaptors (same pattern as e-Solutions) |
| Africa's Talking | SMS, USSD | **Live** (SMS); USSD callback **built** | Register the short code and point it at `/api/v1/psp/channels/ussd/at?token=...` |
| WhatsApp Cloud API | Chat banking, notifications | **Built** | Meta business verification, number, app secret |
| SMTP | Email | **Live** | — |
| Bank payouts | Merchant settlement, wallet withdrawals | **SPI ready**: `BANK_FILE` rail (CSV export + proof upload) | Bank-file upload via your bank's portal; wire the bank's host-to-host API as a `PayoutRail` |
| ZIPIT | Instant interbank payouts | **SPI ready** (routing rule seeded) | Through your sponsor bank's ZIPIT service, as a `PayoutRail` |
| ZIPSS (RTGS) | High-value payouts | **SPI ready** | Bank file / sponsor bank |
| Trust account statements | Reconciliation | **Built**: CSV import | Automate with the bank's MT940/CAMT.053 feed when available |
| National ID (Registrar-General) | KYC tier 2 | **SPI ready**: `MANUAL` verifier (officer confirms the ID, four-eyes for tier 3) | Wire the verifier when API access is granted |
| Credit bureau | Merchant/agent due diligence | **SPI ready**: `MANUAL` | Wire FCB or another bureau |
| Sanctions lists | AML | **Built**: UN XML, OFAC CSV parsers; local and PEP CSV upload | Turn on `PSP_AML_FEEDS` if the server may reach UN/Treasury hosts, else upload |
| RBZ FX rates | ZiG/USD | **Built**: rates entered four-eyes; ingest hook | Wire the RBZ rate feed when available |
| Card acquiring (international) | Visa/Mastercard | Not built | Needs an acquiring bank. PCI DSS scope: keep card data off PayPort servers (hosted fields) |

`payport.psp.integrations.sandbox=true` enables simulated rails, ID verification and bureau for test environments. It must be `false` in production. The sandbox adapters refuse to load unless this flag is set.
