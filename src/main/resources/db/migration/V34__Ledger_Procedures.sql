-- =====================================================================
-- Phase 1: ledger write path (ADR-0008). Every write to ledger_* tables happens here.
--
-- All procedures are SQL SECURITY DEFINER: they run with the privileges of the user who
-- ran this migration (the migration user). The application user is granted EXECUTE on
-- the public procedures only (scripts/db/grant-least-privilege.sh):
--   ledger_open_account, ledger_post, ledger_reverse, ledger_close_period,
--   ledger_reopen_period, ledger_seal, ledger_integrity_report
-- ledger_post_internal is NOT granted to the application.
--
-- Errors are raised with SQLSTATE 45000 and a message starting with a stable code:
--   LEDGER_AUTOCOMMIT, LEDGER_INVALID, LEDGER_INVALID_LINE, LEDGER_UNKNOWN_ACCOUNT,
--   LEDGER_ACCOUNT_CLOSED, LEDGER_ENTITY_MISMATCH, LEDGER_UNBALANCED, LEDGER_PERIOD_CLOSED,
--   LEDGER_INSUFFICIENT_FUNDS, LEDGER_IDEMPOTENCY_CONFLICT, LEDGER_NOT_FOUND,
--   LEDGER_ALREADY_REVERSED, LEDGER_CANNOT_REVERSE_REVERSAL, LEDGER_ACCOUNT_NOT_INITIALISED
-- No stored FUNCTIONS are used: under binlog they would need SUPER or
-- log_bin_trust_function_creators (finding D2). Procedures do not.
-- =====================================================================

DELIMITER $$

-- ── Accounts ──────────────────────────────────────────────────────────

CREATE PROCEDURE ledger_open_account(
    IN  p_entity   VARCHAR(30),
    IN  p_book     VARCHAR(4),
    IN  p_category VARCHAR(40),
    IN  p_owner    VARCHAR(100),
    IN  p_currency CHAR(3),
    IN  p_by       VARCHAR(120),
    OUT p_account_id BIGINT)
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    DECLARE v_ok INT DEFAULT 0;
    IF @@session.autocommit = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_AUTOCOMMIT: call inside a transaction';
    END IF;
    IF p_book NOT IN ('LIVE', 'TEST') OR p_owner IS NULL OR p_owner = '' OR p_owner LIKE '%:%' OR p_by IS NULL OR p_by = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: book, owner and actor are required; owner may not contain ":"';
    END IF;
    SELECT COUNT(*) INTO v_ok FROM ref_legal_entities WHERE code = p_entity AND status = 'ACTIVE';
    IF v_ok = 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: unknown or inactive legal entity'; END IF;
    SELECT COUNT(*) INTO v_ok FROM ref_currencies WHERE code = p_currency AND enabled = TRUE;
    IF v_ok = 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: unknown or disabled currency'; END IF;
    SELECT COUNT(*) INTO v_ok FROM ledger_categories WHERE category = p_category;
    IF v_ok = 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: unknown account category'; END IF;

    INSERT INTO ledger_accounts (code, entity_code, book, category, owner_ref, currency, status, created_at, created_by)
    VALUES (CONCAT_WS(':', p_entity, p_book, p_category, p_owner, p_currency), p_entity, p_book, p_category, p_owner, p_currency,
            'OPEN', NOW(6), p_by)
    ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id);
    SET p_account_id = LAST_INSERT_ID();

    INSERT IGNORE INTO ledger_balances (account_id, debit_total, credit_total, balance, last_entry_id, updated_at)
    VALUES (p_account_id, 0, 0, 0, NULL, NOW(6));
END$$

-- ── Posting (internal: also used by reversals) ────────────────────────

CREATE PROCEDURE ledger_post_internal(
    IN  p_req          JSON,
    IN  p_reverses_id  BIGINT,
    OUT p_entry_id     BIGINT,
    OUT p_outcome      VARCHAR(20))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
proc: BEGIN
    DECLARE v_key, v_ref, v_entity, v_template, v_desc, v_src_type, v_src_ref, v_by VARCHAR(500);
    DECLARE v_book VARCHAR(4);
    DECLARE v_hash CHAR(64);
    DECLARE v_version INT;
    DECLARE v_date DATE;
    DECLARE v_period CHAR(7);
    DECLARE v_existing_id BIGINT DEFAULT NULL;
    DECLARE v_existing_hash CHAR(64) DEFAULT NULL;
    DECLARE v_count, v_bad, v_accounts, v_locked INT DEFAULT 0;
    DECLARE v_pstatus VARCHAR(10) DEFAULT NULL;
    DECLARE v_lines LONGTEXT;
    DECLARE v_type VARCHAR(10);

    IF @@session.autocommit = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_AUTOCOMMIT: call inside a transaction';
    END IF;
    IF p_req IS NULL OR JSON_TYPE(p_req) <> 'OBJECT' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: request must be a JSON object';
    END IF;

    SET v_key      = JSON_VALUE(p_req, '$.idempotency_key');
    SET v_hash     = JSON_VALUE(p_req, '$.request_hash');
    SET v_ref      = JSON_VALUE(p_req, '$.entry_ref');
    SET v_entity   = JSON_VALUE(p_req, '$.entity');
    SET v_book     = JSON_VALUE(p_req, '$.book');
    SET v_template = JSON_VALUE(p_req, '$.template_code');
    SET v_version  = JSON_VALUE(p_req, '$.template_version' RETURNING SIGNED);
    SET v_desc     = JSON_VALUE(p_req, '$.description');
    SET v_src_type = JSON_VALUE(p_req, '$.source_type');
    SET v_src_ref  = JSON_VALUE(p_req, '$.source_ref');
    SET v_by       = JSON_VALUE(p_req, '$.posted_by');
    SET v_date     = JSON_VALUE(p_req, '$.effective_date' RETURNING DATE);
    SET v_type     = IF(p_reverses_id IS NULL, 'POSTING', 'REVERSAL');

    IF v_key IS NULL OR v_key = '' OR CHAR_LENGTH(v_key) > 190
       OR v_hash IS NULL OR v_hash NOT REGEXP '^[0-9a-f]{64}$'
       OR v_ref IS NULL OR v_ref = '' OR CHAR_LENGTH(v_ref) > 40
       OR v_entity IS NULL OR v_book IS NULL OR v_book NOT IN ('LIVE', 'TEST')
       OR v_by IS NULL OR v_by = '' OR v_date IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: idempotency_key, request_hash, entry_ref, entity, book, posted_by and effective_date are required';
    END IF;

    -- 1. Idempotency: same key + same request = the existing entry; same key + different request = error.
    SELECT id, request_hash INTO v_existing_id, v_existing_hash
      FROM ledger_entries WHERE idempotency_key = v_key;
    IF v_existing_id IS NOT NULL THEN
        IF v_existing_hash = v_hash THEN
            SET p_entry_id = v_existing_id;
            SET p_outcome = 'DUPLICATE';
            LEAVE proc;
        END IF;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_IDEMPOTENCY_CONFLICT: key already used for a different request';
    END IF;

    -- 2. Lines into a working table.
    DROP TEMPORARY TABLE IF EXISTS tmp_ledger_lines;
    CREATE TEMPORARY TABLE tmp_ledger_lines (
        line_no        INT           NOT NULL PRIMARY KEY,
        account_code   VARCHAR(200)  NULL,
        direction      VARCHAR(10)   NULL,
        amount_raw     DECIMAL(30,6) NULL,
        memo           VARCHAR(200)  NULL,
        account_id     BIGINT        NULL,
        currency       CHAR(3)       NULL,
        acct_entity    VARCHAR(30)   NULL,
        acct_book      VARCHAR(4)    NULL,
        acct_status    VARCHAR(10)   NULL,
        allow_negative BOOLEAN       NULL,
        normal_side    VARCHAR(6)    NULL
    ) ENGINE = InnoDB;

    INSERT INTO tmp_ledger_lines (line_no, account_code, direction, amount_raw, memo)
    SELECT jt.ord, jt.account_code, jt.direction, jt.amount_raw, jt.memo
      FROM JSON_TABLE(p_req, '$.lines[*]' COLUMNS (
               ord          FOR ORDINALITY,
               account_code VARCHAR(200)  PATH '$.account_code',
               direction    VARCHAR(10)   PATH '$.direction',
               amount_raw   DECIMAL(30,6) PATH '$.amount_minor' NULL ON EMPTY NULL ON ERROR,
               memo         VARCHAR(200)  PATH '$.memo')) AS jt;

    SELECT COUNT(*) INTO v_count FROM tmp_ledger_lines;
    IF v_count < 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: an entry needs at least two lines';
    END IF;
    IF v_count > 500 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: at most 500 lines per entry';
    END IF;

    SELECT COUNT(*) INTO v_bad FROM tmp_ledger_lines
     WHERE account_code IS NULL OR direction IS NULL OR direction NOT IN ('DEBIT', 'CREDIT')
        OR amount_raw IS NULL OR amount_raw <= 0 OR amount_raw <> FLOOR(amount_raw)
        OR amount_raw > 999999999999999;
    IF v_bad > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID_LINE: every line needs an account, DEBIT or CREDIT, and a positive whole amount in minor units';
    END IF;

    -- 3. Resolve accounts.
    UPDATE tmp_ledger_lines t
      JOIN ledger_accounts a   ON a.code = t.account_code
      JOIN ledger_categories c ON c.category = a.category
       SET t.account_id = a.id, t.currency = a.currency, t.acct_entity = a.entity_code, t.acct_book = a.book,
           t.acct_status = a.status, t.allow_negative = c.allow_negative, t.normal_side = c.normal_side;

    SELECT COUNT(*) INTO v_bad FROM tmp_ledger_lines WHERE account_id IS NULL;
    IF v_bad > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_UNKNOWN_ACCOUNT: open the account first'; END IF;
    SELECT COUNT(*) INTO v_bad FROM tmp_ledger_lines WHERE acct_status <> 'OPEN';
    IF v_bad > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_ACCOUNT_CLOSED: an account in this entry is closed'; END IF;
    SELECT COUNT(*) INTO v_bad FROM tmp_ledger_lines WHERE acct_entity <> v_entity OR acct_book <> v_book;
    IF v_bad > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_ENTITY_MISMATCH: every account must belong to the entry''s entity and book'; END IF;

    -- 4. Debits = credits, per currency. Never nets across currencies.
    SELECT COUNT(*) INTO v_bad FROM (
        SELECT currency FROM tmp_ledger_lines
         GROUP BY currency
        HAVING SUM(IF(direction = 'DEBIT', amount_raw, -amount_raw)) <> 0) AS unbalanced;
    IF v_bad > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_UNBALANCED: debits must equal credits in every currency'; END IF;

    -- 5. Period must be open. The shared lock makes ledger_close_period wait for in-flight postings.
    SET v_period = DATE_FORMAT(v_date, '%Y-%m');
    INSERT IGNORE INTO ledger_periods (entity_code, book, period, status, changed_by, changed_at)
    VALUES (v_entity, v_book, v_period, 'OPEN', 'SYSTEM:auto-open', NOW(6));
    SELECT status INTO v_pstatus FROM ledger_periods
     WHERE entity_code = v_entity AND book = v_book AND period = v_period FOR SHARE;
    IF v_pstatus <> 'OPEN' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_PERIOD_CLOSED: the accounting period of effective_date is closed';
    END IF;

    -- 6. Lock balances one by one in ascending account id. A single set-based FOR UPDATE does not
    -- guarantee lock order (the optimizer drove it from the line order and opposing transfers
    -- deadlocked: found by LedgerConcurrencyTest), so an explicit ordered cursor is used.
    SELECT COUNT(DISTINCT account_id) INTO v_accounts FROM tmp_ledger_lines;
    lock_block: BEGIN
        DECLARE v_done INT DEFAULT 0;
        DECLARE v_acc BIGINT;
        DECLARE v_seen BIGINT;
        DECLARE cur_accounts CURSOR FOR SELECT DISTINCT account_id FROM tmp_ledger_lines ORDER BY account_id;
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;
        OPEN cur_accounts;
        lock_loop: LOOP
            FETCH cur_accounts INTO v_acc;
            IF v_done = 1 THEN LEAVE lock_loop; END IF;
            SET v_seen = NULL;
            SELECT account_id INTO v_seen FROM ledger_balances WHERE account_id = v_acc FOR UPDATE;
            IF v_seen IS NOT NULL THEN SET v_locked = v_locked + 1; END IF;
            SET v_done = 0;
        END LOOP;
        CLOSE cur_accounts;
    END lock_block;
    IF v_locked <> v_accounts THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_ACCOUNT_NOT_INITIALISED: balance row missing';
    END IF;

    -- 7. No-negative accounts may not go below zero.
    -- MUST be a locking read: under REPEATABLE READ a plain SELECT reads the transaction's
    -- snapshot, not the row just locked, and concurrent spends would each see the old
    -- balance (found by LedgerConcurrencyTest: 163 of 200 debits passed against funds for 100).
    SELECT COUNT(*) INTO v_bad
      FROM ledger_balances b
      JOIN (SELECT account_id, MAX(normal_side) AS side, MAX(allow_negative) AS neg,
                   SUM(IF(direction = 'DEBIT', amount_raw, 0)) AS d, SUM(IF(direction = 'CREDIT', amount_raw, 0)) AS c
              FROM tmp_ledger_lines GROUP BY account_id) AS x ON x.account_id = b.account_id
     WHERE x.neg = 0 AND b.balance + IF(x.side = 'DEBIT', x.d - x.c, x.c - x.d) < 0
       FOR UPDATE OF b;
    IF v_bad > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INSUFFICIENT_FUNDS: an account that may not go negative would go below zero';
    END IF;

    -- 8. Write. The content hash covers the entry and its lines; the integrity report recomputes it.
    SET SESSION group_concat_max_len = 4194304;
    SELECT GROUP_CONCAT(CONCAT_WS(',', line_no, account_code, direction, CAST(amount_raw AS SIGNED)) ORDER BY line_no SEPARATOR ';')
      INTO v_lines FROM tmp_ledger_lines;

    INSERT INTO ledger_entries (entry_ref, entity_code, book, idempotency_key, request_hash, entry_type, template_code,
                                template_version, effective_date, period, description, source_type, source_ref,
                                reverses_entry_id, posted_by, posted_at, content_hash)
    VALUES (v_ref, v_entity, v_book, v_key, v_hash, v_type, v_template, v_version, v_date, v_period, v_desc,
            v_src_type, v_src_ref, p_reverses_id, v_by, NOW(6),
            SHA2(CONCAT_WS('|', v_ref, v_key, v_entity, v_book, v_type, IFNULL(v_template, ''), IFNULL(v_version, 0),
                           v_date, IFNULL(p_reverses_id, 0), v_lines), 256));
    SET p_entry_id = LAST_INSERT_ID();

    INSERT INTO ledger_lines (entry_id, line_no, account_id, direction, amount_minor, currency, memo)
    SELECT p_entry_id, line_no, account_id, direction, CAST(amount_raw AS SIGNED), currency, memo
      FROM tmp_ledger_lines ORDER BY line_no;

    UPDATE ledger_balances b
      JOIN (SELECT account_id, MAX(normal_side) AS side,
                   CAST(SUM(IF(direction = 'DEBIT', amount_raw, 0)) AS SIGNED)  AS d,
                   CAST(SUM(IF(direction = 'CREDIT', amount_raw, 0)) AS SIGNED) AS c
              FROM tmp_ledger_lines GROUP BY account_id) AS x ON x.account_id = b.account_id
       SET b.debit_total  = b.debit_total + x.d,
           b.credit_total = b.credit_total + x.c,
           b.balance      = b.balance + IF(x.side = 'DEBIT', x.d - x.c, x.c - x.d),
           b.last_entry_id = p_entry_id,
           b.updated_at   = NOW(6);

    DROP TEMPORARY TABLE IF EXISTS tmp_ledger_lines;
    SET p_outcome = 'POSTED';
END$$

-- ── Posting (public) ──────────────────────────────────────────────────

CREATE PROCEDURE ledger_post(
    IN  p_req      JSON,
    OUT p_entry_id BIGINT,
    OUT p_outcome  VARCHAR(20))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    -- Reversals only through ledger_reverse, which builds the exact mirror itself.
    IF JSON_CONTAINS_PATH(p_req, 'one', '$.reverses_entry_ref', '$.reverses_entry_id') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: use ledger_reverse to reverse an entry';
    END IF;
    CALL ledger_post_internal(p_req, NULL, p_entry_id, p_outcome);
END$$

-- ── Reversal ──────────────────────────────────────────────────────────

CREATE PROCEDURE ledger_reverse(
    IN  p_original_ref   VARCHAR(40),
    IN  p_reversal_ref   VARCHAR(40),
    IN  p_idem_key       VARCHAR(190),
    IN  p_effective_date DATE,
    IN  p_by             VARCHAR(120),
    IN  p_reason         VARCHAR(400),
    OUT p_entry_id       BIGINT,
    OUT p_outcome        VARCHAR(20))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
proc: BEGIN
    DECLARE v_orig_id BIGINT DEFAULT NULL;
    DECLARE v_orig_type VARCHAR(10);
    DECLARE v_entity VARCHAR(30);
    DECLARE v_book VARCHAR(4);
    DECLARE v_template VARCHAR(60);
    DECLARE v_version INT;
    DECLARE v_src_type VARCHAR(40);
    DECLARE v_src_ref VARCHAR(120);
    DECLARE v_existing_id BIGINT DEFAULT NULL;
    DECLARE v_existing_hash CHAR(64);
    DECLARE v_hash CHAR(64);
    DECLARE v_lines JSON;
    DECLARE v_req JSON;

    IF @@session.autocommit = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_AUTOCOMMIT: call inside a transaction';
    END IF;
    IF p_reason IS NULL OR p_reason = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: a reversal needs a reason';
    END IF;

    SET v_hash = SHA2(CONCAT('REVERSE|', IFNULL(p_original_ref, '')), 256);
    SELECT id, request_hash INTO v_existing_id, v_existing_hash FROM ledger_entries WHERE idempotency_key = p_idem_key;
    IF v_existing_id IS NOT NULL THEN
        IF v_existing_hash = v_hash THEN
            SET p_entry_id = v_existing_id; SET p_outcome = 'DUPLICATE'; LEAVE proc;
        END IF;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_IDEMPOTENCY_CONFLICT: key already used for a different request';
    END IF;

    SELECT id, entry_type, entity_code, book, template_code, template_version, source_type, source_ref
      INTO v_orig_id, v_orig_type, v_entity, v_book, v_template, v_version, v_src_type, v_src_ref
      FROM ledger_entries WHERE entry_ref = p_original_ref FOR UPDATE;
    IF v_orig_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_NOT_FOUND: no entry with that reference';
    END IF;
    IF v_orig_type = 'REVERSAL' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_CANNOT_REVERSE_REVERSAL: post a new entry instead';
    END IF;
    IF EXISTS (SELECT 1 FROM ledger_entries WHERE reverses_entry_id = v_orig_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_ALREADY_REVERSED: this entry has already been reversed';
    END IF;

    SELECT JSON_ARRAYAGG(JSON_OBJECT(
               'account_code', a.code,
               'direction', IF(l.direction = 'DEBIT', 'CREDIT', 'DEBIT'),
               'amount_minor', l.amount_minor,
               'memo', CONCAT('Reversal of line ', l.line_no)))
      INTO v_lines
      FROM ledger_lines l JOIN ledger_accounts a ON a.id = l.account_id
     WHERE l.entry_id = v_orig_id;

    SET v_req = JSON_OBJECT(
        'idempotency_key', p_idem_key, 'request_hash', v_hash, 'entry_ref', p_reversal_ref,
        'entity', v_entity, 'book', v_book, 'template_code', v_template, 'template_version', v_version,
        'effective_date', IFNULL(p_effective_date, CURRENT_DATE()), 'description', CONCAT('Reversal: ', p_reason),
        'source_type', v_src_type, 'source_ref', v_src_ref, 'posted_by', p_by, 'lines', v_lines);

    CALL ledger_post_internal(v_req, v_orig_id, p_entry_id, p_outcome);
END$$

-- ── Periods ───────────────────────────────────────────────────────────

CREATE PROCEDURE ledger_close_period(IN p_entity VARCHAR(30), IN p_book VARCHAR(4), IN p_period CHAR(7), IN p_by VARCHAR(120))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    IF @@session.autocommit = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_AUTOCOMMIT: call inside a transaction';
    END IF;
    IF p_by IS NULL OR p_by = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: actor required';
    END IF;
    INSERT INTO ledger_periods (entity_code, book, period, status, changed_by, changed_at)
    VALUES (p_entity, p_book, p_period, 'CLOSED', p_by, NOW(6))
    ON DUPLICATE KEY UPDATE status = 'CLOSED', changed_by = p_by, changed_at = NOW(6);
END$$

-- Re-opening is a controlled action: the application requires an approved four-eyes request first.
CREATE PROCEDURE ledger_reopen_period(IN p_entity VARCHAR(30), IN p_book VARCHAR(4), IN p_period CHAR(7), IN p_by VARCHAR(120))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    IF @@session.autocommit = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_AUTOCOMMIT: call inside a transaction';
    END IF;
    IF p_by IS NULL OR p_by = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_INVALID: actor required';
    END IF;
    UPDATE ledger_periods SET status = 'OPEN', changed_by = p_by, changed_at = NOW(6)
     WHERE entity_code = p_entity AND book = p_book AND period = p_period;
END$$

-- ── Sealing ───────────────────────────────────────────────────────────
-- Seals entries posted more than p_settle_seconds ago, in id order, up to p_max entries.
-- The settle window guarantees no transaction can still commit an entry with an id inside
-- the sealed range (posting transactions are short and bounded by the lock wait timeout).

CREATE PROCEDURE ledger_seal(IN p_by VARCHAR(120), IN p_settle_seconds INT, IN p_max INT, OUT p_seal_id BIGINT, OUT p_count INT)
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
proc: BEGIN
    DECLARE v_from, v_to BIGINT DEFAULT NULL;
    DECLARE v_prev CHAR(64) DEFAULT NULL;
    DECLARE v_last_to BIGINT DEFAULT 0;
    DECLARE v_concat LONGTEXT;
    DECLARE v_lock INT;
    DECLARE v_max INT;
    DECLARE v_settle INT;

    IF @@session.autocommit = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LEDGER_AUTOCOMMIT: call inside a transaction';
    END IF;
    SET p_seal_id = NULL;
    SET p_count = 0;
    SET v_max = GREATEST(IFNULL(p_max, 10000), 1);
    SET v_settle = GREATEST(IFNULL(p_settle_seconds, 60), 0);
    SELECT id INTO v_lock FROM ledger_seal_lock WHERE id = 1 FOR UPDATE;

    SELECT to_entry_id, seal_hash INTO v_last_to, v_prev FROM ledger_seals ORDER BY id DESC LIMIT 1;
    SET v_last_to = IFNULL(v_last_to, 0);

    SELECT MIN(id), MAX(id), COUNT(*) INTO v_from, v_to, p_count FROM (
        SELECT id FROM ledger_entries
         WHERE id > v_last_to AND posted_at < NOW(6) - INTERVAL v_settle SECOND
         ORDER BY id LIMIT v_max) AS batch;
    IF p_count = 0 THEN LEAVE proc; END IF;

    SET SESSION group_concat_max_len = 16777216;
    SELECT GROUP_CONCAT(content_hash ORDER BY id SEPARATOR '') INTO v_concat
      FROM ledger_entries WHERE id BETWEEN v_from AND v_to;
    -- Entries in the range must be exactly the batch (no gaps appearing later).
    SET p_count = (SELECT COUNT(*) FROM ledger_entries WHERE id BETWEEN v_from AND v_to);

    INSERT INTO ledger_seals (from_entry_id, to_entry_id, entry_count, prev_seal_hash, seal_hash, sealed_at, sealed_by)
    VALUES (v_from, v_to, p_count, v_prev, SHA2(CONCAT(IFNULL(v_prev, ''), v_concat), 256), NOW(6), p_by);
    SET p_seal_id = LAST_INSERT_ID();
END$$

-- ── Integrity report ──────────────────────────────────────────────────
-- Returns one row per violation: (check_code, subject, detail). Empty result = healthy.

CREATE PROCEDURE ledger_integrity_report()
    SQL SECURITY DEFINER
    READS SQL DATA
BEGIN
    SET SESSION group_concat_max_len = 16777216;

    SELECT 'UNBALANCED_ENTRY' AS check_code, e.entry_ref AS subject,
           CONCAT(l.currency, ' net ', SUM(IF(l.direction = 'DEBIT', l.amount_minor, -l.amount_minor))) AS detail
      FROM ledger_lines l JOIN ledger_entries e ON e.id = l.entry_id
     GROUP BY e.entry_ref, l.currency
    HAVING SUM(IF(l.direction = 'DEBIT', l.amount_minor, -l.amount_minor)) <> 0
    UNION ALL
    SELECT 'TOO_FEW_LINES', e.entry_ref, CONCAT(COUNT(l.id), ' line(s)')
      FROM ledger_entries e LEFT JOIN ledger_lines l ON l.entry_id = e.id
     GROUP BY e.entry_ref HAVING COUNT(l.id) < 2
    UNION ALL
    SELECT 'BALANCE_MISMATCH', a.code,
           CONCAT('projection ', b.balance, ' / ', b.debit_total, ' / ', b.credit_total,
                  ' vs lines ', IF(c.normal_side = 'DEBIT', IFNULL(s.d, 0) - IFNULL(s.c, 0), IFNULL(s.c, 0) - IFNULL(s.d, 0)),
                  ' / ', IFNULL(s.d, 0), ' / ', IFNULL(s.c, 0))
      FROM ledger_accounts a
      JOIN ledger_categories c ON c.category = a.category
      LEFT JOIN ledger_balances b ON b.account_id = a.id
      LEFT JOIN (SELECT account_id, SUM(IF(direction = 'DEBIT', amount_minor, 0)) AS d,
                        SUM(IF(direction = 'CREDIT', amount_minor, 0)) AS c
                   FROM ledger_lines GROUP BY account_id) AS s ON s.account_id = a.id
     WHERE b.account_id IS NULL
        OR b.debit_total <> IFNULL(s.d, 0) OR b.credit_total <> IFNULL(s.c, 0)
        OR b.balance <> IF(c.normal_side = 'DEBIT', IFNULL(s.d, 0) - IFNULL(s.c, 0), IFNULL(s.c, 0) - IFNULL(s.d, 0))
    UNION ALL
    SELECT 'NEGATIVE_PROTECTED', a.code, CONCAT('balance ', b.balance)
      FROM ledger_accounts a JOIN ledger_categories c ON c.category = a.category
      JOIN ledger_balances b ON b.account_id = a.id
     WHERE c.allow_negative = FALSE AND b.balance < 0
    UNION ALL
    SELECT 'LINE_CURRENCY_MISMATCH', CONCAT(e.entry_ref, '#', l.line_no), CONCAT(l.currency, ' on ', a.code)
      FROM ledger_lines l JOIN ledger_accounts a ON a.id = l.account_id JOIN ledger_entries e ON e.id = l.entry_id
     WHERE l.currency <> a.currency
    UNION ALL
    SELECT 'REVERSAL_NOT_MIRROR', r.entry_ref, CONCAT('account ', x.account_id, ' net ', x.net)
      FROM ledger_entries r
      JOIN (SELECT r2.id AS rid, l.account_id, SUM(IF(l.direction = 'DEBIT', l.amount_minor, -l.amount_minor)) AS net
              FROM ledger_entries r2
              JOIN ledger_lines l ON l.entry_id IN (r2.id, r2.reverses_entry_id)
             WHERE r2.entry_type = 'REVERSAL'
             GROUP BY r2.id, l.account_id
            HAVING net <> 0) AS x ON x.rid = r.id
    UNION ALL
    SELECT 'CONTENT_HASH_MISMATCH', e.entry_ref, 'entry or lines changed after posting'
      FROM ledger_entries e
      JOIN (SELECT l.entry_id,
                   GROUP_CONCAT(CONCAT_WS(',', l.line_no, a.code, l.direction, l.amount_minor) ORDER BY l.line_no SEPARATOR ';') AS s
              FROM ledger_lines l JOIN ledger_accounts a ON a.id = l.account_id
             GROUP BY l.entry_id) AS x ON x.entry_id = e.id
     WHERE e.content_hash <> SHA2(CONCAT_WS('|', e.entry_ref, e.idempotency_key, e.entity_code, e.book, e.entry_type,
                                            IFNULL(e.template_code, ''), IFNULL(e.template_version, 0), e.effective_date,
                                            IFNULL(e.reverses_entry_id, 0), x.s), 256)
    UNION ALL
    SELECT 'SEAL_MISMATCH', CONCAT('seal ', s.id), 'sealed entries changed, missing or added'
      FROM ledger_seals s
     WHERE s.seal_hash <> SHA2(CONCAT(IFNULL(s.prev_seal_hash, ''),
                                      IFNULL((SELECT GROUP_CONCAT(e.content_hash ORDER BY e.id SEPARATOR '')
                                                FROM ledger_entries e
                                               WHERE e.id BETWEEN s.from_entry_id AND s.to_entry_id), '')), 256)
        OR s.entry_count <> (SELECT COUNT(*) FROM ledger_entries e WHERE e.id BETWEEN s.from_entry_id AND s.to_entry_id)
    UNION ALL
    SELECT 'SEAL_CHAIN_BROKEN', CONCAT('seal ', s.id), 'previous seal hash does not match'
      FROM ledger_seals s
      JOIN ledger_seals p ON p.id = (SELECT MAX(id) FROM ledger_seals WHERE id < s.id)
     WHERE NOT (s.prev_seal_hash <=> p.seal_hash) OR s.from_entry_id <> p.to_entry_id + 1;
END$$

DELIMITER ;
