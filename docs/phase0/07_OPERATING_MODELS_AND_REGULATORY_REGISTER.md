# Phase 0: Operating models, global settlement feasibility and regulatory register

**I am not a lawyer.** This document collects what I could verify from public sources and sets out the questions qualified Zimbabwean counsel and RBZ must answer. Sources are at the end. Each fact is dated by its source.

## 1. What I could establish (Zimbabwe)

| Topic | Finding | Confidence |
|---|---|---|
| Payment regulator | RBZ supervises payment systems under the National Payment Systems Act | High |
| PSP licensing | SI 17 of 2025 (28 Feb 2025) amended the Banking (Money Transmission, Mobile Banking and Money Interoperability) Regulations (SI 80 of 2020). It sets a **USD 5,000 application fee** for a new PSP licence and an **annual fee of 2% of gross turnover, capped at USD 50,000**. **Non-bank institutions must partner with a locally authorised financial institution, which supports the application** | Medium (secondary source) |
| Customer funds | Segregated trust account required for e-money / mobile money | Medium (secondary source) |
| Agents | RBZ prior written approval for third-party agent contracts (Prudential Standards on Agency Banking) | Medium |
| QR payments | RBZ *Guidelines for QR Code Payments* (effective 11 Mar 2026):<br>• EMVCo standard; dynamic QR preferred; static only under USD 50<br>• inter-provider routing via the **National Switch** by default<br>• scheme MAI ID via the Switch; closed-loop QR must cease<br>• merchant value T+1<br>• disputes resolved within 14 working days<br>• breaches reported within 3 hours | Medium-high (document text via aggregator) |
| Cross-border remittance / FX | Authorised Dealers with Limited Authority (ADLA) regime: money transfer agencies, bureaux de change | Medium |
| Tax | From 1 Jan 2026 (Finance Act No. 7 of 2025, as reported): **IMTT 2% on USD, 1.5% on ZiG; VAT 15.5%** | Medium (press) |
| Data protection | Cyber and Data Protection Act, enforced by POTRAZ. SI 155 of 2024 (in force 12 Sep 2024) requires:<br>• registration or **licensing of data controllers**<br>• a **DPO** for licensed controllers<br>• **breach notice to POTRAZ within 24 h**<br>• notice to POTRAZ before transferring personal data abroad | Medium (secondary source) |
| AML/CFT | Money Laundering and Proceeds of Crime Act; FIU reporting (STR, CTR) | Known framework; current thresholds and formats not verified |
| Regional rails | RBZ began implementing PAPSS in 2023; participation is through banks | Medium |

**Not established and must be answered:**
- Payport's current licence or registration status.
- Who holds collected funds today.
- Which licence covers merchant acquiring / aggregation specifically.
- Minimum capital for Payport's category.
- Whether Payport is an IMTT agent.
- Data-hosting expectations.
- ZIPSS / National Switch message standards.

## 2. Operating models compared

| | **A. Direct PSP** | **B. Partner-led settlement** | **C. Merchant aggregation (payfac-style)** | **D. Hybrid** |
|---|---|---|---|---|
| What it is | Payport is licensed by RBZ, holds collections in its own trust accounts and pays merchants | A licensed bank/PSP holds the funds and executes payouts; Payport provides technology, onboarding and instructions | Payport contracts merchants as sub-merchants under an acquirer/provider's master account; the provider settles to Payport or directly to merchants | B or C at launch for each market or method, moving to A where licensed |
| Who holds funds | Payport (trust) | Partner | Provider or acquirer (sometimes Payport as master merchant) | Varies by market |
| Licences | RBZ PSP licence (non-banks via a sponsoring institution); trust arrangement; data controller licence | Partner's licence; Payport may still need registration as a technical service provider | Provider's licence; RBZ view on aggregation required | Per market |
| Payport's ledger role | **Book of record** for customer funds; must reconcile to trust bank | **Shadow ledger** of the partner's books; reconciles to partner statements | Ledger of receivables from provider and payables to merchants | Both |
| Settlement control | Full (subject to approvals) | Instruction only; partner executes | Limited; depends on provider's sub-merchant settlement | — |
| Speed to market | Slowest (licensing 3–6+ months per secondary source) | Fastest | Fast for supported methods | Fast start, strategic end-state |
| Capital and compliance burden | Highest (safeguarding, audits, reporting, AML programme) | Lowest; shared with partner | Moderate; KYB obligations still Payport's | Grows over time |
| Commercial margin | Highest | Lowest (partner fee) | Moderate | — |
| Main risk | Licence and safeguarding failures are Payport's | Dependency on one partner (contradicts "no external dependency"; mitigated by multi-partner design) | Concentration risk; provider may cap merchants | Complexity of two models |

**Recommendation [for decision]:**
- **Design for D (hybrid).**
- **Zimbabwe launch:** B with a sponsor bank, or A if Payport already holds or is close to a licence. Because non-banks apply with a sponsoring institution, a bank relationship is needed in either case.
- **Other markets:** start with B or C in each country until volume justifies local licensing.

The architecture supports all four because:
- the ledger always runs;
- each market enablement names its **legal entity and settlement model**;
- payouts go through the provider-independent payout layer, and "partner executes" is just another payout adapter.

## 3. Feasibility: register once, accept everywhere, settle anywhere

| Element | Feasible? | Conditions |
|---|---|---|
| One merchant account across markets | **Yes** | Per-market KYB and approval; the contracting entity can differ by market |
| One integration (API, keys, checkout) | **Yes** | Method availability computed per market |
| Consolidated dashboard | **Yes** | Figures tagged by entity and currency; no cross-currency sums without FX |
| Local settlement in each market | **Yes**, per market | Local entity or partner, local payout rail |
| Cross-border settlement (collect in ZW, pay out abroad) | **Conditional** | Exchange control approval or a licensed cross-border route (bank / ADLA / PAPSS via a bank); compliance checks; FX at an approved rate. **Cannot be assumed.** |
| Payport holding funds in a new country | **Conditional** | Local licence or partner |

## 4. Regulatory obligations register (initial)

**Owner** is the role to be assigned by you. **Status** is the state today.

| # | Obligation | Source | Owner | Status | Evidence needed |
|---|---|---|---|---|---|
| O1 | Licence or authorisation for each activity performed (acquiring, collections, payouts, e-money, agents, QR) | NPS Act; SI 80/2020 as amended by SI 17/2025 | CEO / counsel | **Unknown** | Licence or opinion letter |
| O2 | Safeguarding / trust account for customer funds | RBZ | Finance | Unknown | Trust deed, bank letter, daily reconciliation |
| O3 | AML/CFT programme: KYC/KYB, screening, monitoring, STR/CTR, record keeping | MLPC Act, FIU | MLRO | Unknown | Policy, appointed MLRO, FIU registration |
| O4 | Data controller licence, DPO, breach notification (24 h), cross-border transfer notice | CDPA; SI 155/2024 | DPO | Unknown | POTRAZ licence, DPO appointment |
| O5 | IMTT and VAT treatment of Payport's flows and fees | Finance Act | Finance / tax adviser | Unknown | Tax opinion |
| O6 | QR participation via the National Switch scheme | RBZ QR Guidelines 2026 | Product | Not started | Scheme agreement, MAI ID |
| O7 | RBZ incident and fraud reporting timelines | RBZ guidelines / cybersecurity framework | CISO | Not started | Runbook, contacts |
| O8 | Record retention periods (financial, KYC, transaction) | MLPC Act, tax law | Compliance | Unknown | Retention schedule |
| O9 | PCI DSS scope (currently hosted ZimSwitch widget) | PCI SSC | CISO | Assumed minimal scope (SAQ A / A-EP) | SAQ |
| O10 | Consumer protection: disclosures, complaints, dispute timelines | RBZ | Ops | Unknown | Policies |

## Sources

- [Afriwise: Understanding Zimbabwe's New Licensing Regulations for Money Transmission, Mobile Banking and Money Interoperability](https://www.afriwise.com/blog/understanding-zimbabwes-new-licensing-regulations-for-money-transmission-mobile-banking-and-money-interoperability)
- [RegAlert: Zimbabwe fintech & payments regulation](https://regalert.today/guides/fintech/ZW)
- [RegAlert: RBZ Guidelines for QR Code Payments in Zimbabwe (2026), text](https://regalert.today/document/7cacaccd-5b02-48c7-a381-ae5087ae710c/text)
- [The Herald: Several new taxes kick in (11 Jan 2026)](https://www.heraldonline.co.zw/several-new-taxes-kick-in/)
- [AllAfrica: Finance Minister unveils new IMTT and VAT measures for 2026](https://allafrica.com/stories/202511280037.html)
- [Michalsons: Zimbabwe's Cyber and Data Protection Act overview](https://www.michalsons.com/blog/zimbabwes-cyber-and-data-protection-act-overview/78795)
- [The Herald: RBZ implements Pan-African payment system (2023)](https://www.heraldonline.co.zw/rbz-implements-pan-african-payment-system/)
- [ZimLII: National Payment Systems Act](https://zimlii.org/akn/zw/act/2001/21)
