-- =====================================================================
-- Phase 2, slice 2.2: durable fulfilment jobs (replaces the in-memory queue,
-- which lost every queued delivery on restart: R12).
--
-- One row per payment to deliver. The job is written in the same database
-- transaction that confirms the payment, so a confirmed payment always has a job
-- and a job never runs before the confirmation is committed.
--
-- status:
--   QUEUED   due at next_run_at
--   RUNNING  claimed by lease_owner until lease_until; a lease that expires means the
--            instance died mid-delivery: the job becomes UNKNOWN, never re-delivered
--   UNKNOWN  the delivery may have happened; resolved by the provider's status query
--   DONE     delivered (or nothing left to do)
--   FAILED   the provider definitely did not deliver; ops can retry (re-queues the job)
--   MANUAL   no definite answer after the resolution window; a person must check
-- =====================================================================
CREATE TABLE fulfilment_jobs (
    transaction_reference  VARCHAR(100)  NOT NULL PRIMARY KEY,
    status                 VARCHAR(10)   NOT NULL,
    attempts               INT           NOT NULL DEFAULT 0,
    next_run_at            DATETIME(6)   NOT NULL,
    lease_owner            VARCHAR(100)  NULL,
    lease_until            DATETIME(6)   NULL,
    unknown_since          DATETIME(6)   NULL,
    last_result            VARCHAR(1000) NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    CONSTRAINT chk_fjob_status CHECK (status IN ('QUEUED','RUNNING','UNKNOWN','DONE','FAILED','MANUAL')),
    INDEX idx_fjob_due (status, next_run_at),
    INDEX idx_fjob_lease (status, lease_until)
);
