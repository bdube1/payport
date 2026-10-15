@entity LedgerAccount psp_ledger_accounts zw.co.payport.gateway.psp.ledger
@doc One account per (category, owner, currency). Created on first use by AccountProvisioner.
@import zw.co.payport.gateway.psp.ledger.domain.AccountCategory
@import zw.co.payport.gateway.psp.ledger.domain.AccountType
@index idx_ledger_acct_owner category,owner_ref
code String len=120 notnull unique updatable=false
category AccountCategory enum len=40 notnull updatable=false
accountType AccountType enum len=20 notnull updatable=false
ownerRef String len=60 notnull updatable=false
currency String len=3 notnull updatable=false
allowNegative boolean notnull
status String len=20 notnull default="OPEN"
@created createdAt

@entity JournalEntry psp_journal_entries zw.co.payport.gateway.psp.ledger
@doc Header of a balanced, immutable journal entry. UPDATE/DELETE blocked by DB triggers; sealed into the LEDGER hash chain.
@import zw.co.payport.gateway.psp.ledger.domain.EntryType
@immutable
@index idx_journal_source source_type,source_ref
@index idx_journal_type_time entry_type,effective_at
entryRef String len=40 notnull unique
idempotencyKey String len=190 notnull unique
entryType EntryType enum len=40 notnull
currency String len=3 notnull
description String len=500
sourceType String len=40
sourceRef String len=120
reversesEntryId Long unique
reversesEntryRef String len=40
effectiveAt LocalDateTime notnull
postedBy String len=120 notnull
entryHash String len=64 notnull
@created createdAt

@entity JournalLine psp_journal_lines zw.co.payport.gateway.psp.ledger
@doc One debit or credit. Balances are SUMs over these rows.
@import zw.co.payport.gateway.psp.ledger.domain.Direction
@immutable
@index idx_jline_account account_id,entry_id
@index idx_jline_entry entry_id
entryId Long notnull
lineNo int notnull
accountId Long notnull
accountCode String len=120 notnull
direction Direction enum len=6 notnull
amountMinor long notnull
currency String len=3 notnull
memo String len=200

@entity AccountBalance psp_account_balances zw.co.payport.gateway.psp.ledger
@doc Running-total projection of journal lines, used for row locking and O(1) overdraft checks.
@doc Not the source of truth: LedgerIntegrityService re-derives it from journal lines nightly and raises a break on any drift.
accountId Long notnull unique
balanceMinor long notnull
debitsMinor long notnull
creditsMinor long notnull
lastEntryId Long
@updated updatedAt

@entity FundHold psp_fund_holds zw.co.payport.gateway.psp.ledger
@doc Tracks a merchant collection through PENDING -> AVAILABLE (+ rolling reserve) on its T+N schedule.
@index idx_hold_release status,release_at
@index idx_hold_reserve reserve_status,reserve_release_at
@index idx_hold_merchant merchant_id,currency
holdRef String len=120 notnull unique
merchantId Long notnull
currency String len=3 notnull
amountMinor long notnull
reserveBps int notnull
reserveDays int notnull
releaseAt LocalDateTime notnull
status String len=20 notnull
releasedAt LocalDateTime
releasedAmountMinor Long
reserveMinor Long
reserveReleaseAt LocalDateTime
reserveStatus String len=20 notnull default="NONE"
reserveReleasedAt LocalDateTime
@created createdAt
@updated updatedAt
