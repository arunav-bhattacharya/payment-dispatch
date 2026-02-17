-- =============================================================================
-- Rate-Limited Payment Dispatch System — Phase 1
-- Oracle DDL — NO Foreign Keys (application-level integrity)
-- =============================================================================

-- 1. EXEC_RATE_CONFIG: Dynamic dispatch configuration per item type
--    Updatable at runtime via SQL — no redeploy needed
CREATE TABLE EXEC_RATE_CONFIG (
    item_type                       VARCHAR2(64)    NOT NULL,
    enabled                         NUMBER(1)       DEFAULT 1 NOT NULL,
    batch_size                      NUMBER(10)      DEFAULT 100 NOT NULL,
    dispatch_interval_secs          NUMBER(10)      DEFAULT 5 NOT NULL,
    jitter_window_ms                NUMBER(10)      DEFAULT 2000 NOT NULL,
    pre_start_buffer_mins           NUMBER(10)      DEFAULT 5 NOT NULL,
    max_dispatch_retries            NUMBER(5)       DEFAULT 3 NOT NULL,
    stale_claim_threshold_mins      NUMBER(5)       DEFAULT 10 NOT NULL,
    exec_workflow_type              VARCHAR2(256)   NOT NULL,
    exec_task_queue                 VARCHAR2(256)   NOT NULL,
    description                     VARCHAR2(512),
    created_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_exec_rate_config PRIMARY KEY (item_type)
);

-- 2. PAYMENT_EXEC_QUEUE: Dispatch queue (multi-type via item_type)
--    Status lifecycle: READY → CLAIMED → DISPATCHED (terminal — Temporal manages execution)
CREATE TABLE PAYMENT_EXEC_QUEUE (
    payment_id                      VARCHAR2(128)   NOT NULL,
    item_type                       VARCHAR2(64)    DEFAULT 'PAYMENT' NOT NULL,
    queue_status                    VARCHAR2(32)    DEFAULT 'READY' NOT NULL,
    scheduled_exec_time             TIMESTAMP       NOT NULL,
    init_workflow_id                VARCHAR2(256),
    dispatch_batch_id               VARCHAR2(64),
    exec_workflow_id                VARCHAR2(256),
    claimed_at                      TIMESTAMP,
    dispatched_at                   TIMESTAMP,
    retry_count                     NUMBER(5)       DEFAULT 0 NOT NULL,
    last_error                      VARCHAR2(4000),
    created_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_payment_exec_queue PRIMARY KEY (payment_id)
);

-- Primary dispatch query: claimBatch filters on status + scheduled time + retries
CREATE INDEX idx_peq_dispatch ON PAYMENT_EXEC_QUEUE (
    item_type, queue_status, scheduled_exec_time, retry_count
);

-- Unified claim query also picks up stale CLAIMED rows via OR predicate
CREATE INDEX idx_peq_stale_claims ON PAYMENT_EXEC_QUEUE (
    item_type, queue_status, claimed_at
);

-- Batch lookup after claiming
CREATE INDEX idx_peq_batch ON PAYMENT_EXEC_QUEUE (dispatch_batch_id);

-- 3. PAYMENT_EXEC_CONTEXT: JSON CLOB storage for execution context
--    Accumulated state from Phase A, consumed by Phase B
CREATE TABLE PAYMENT_EXEC_CONTEXT (
    payment_id                      VARCHAR2(128)   NOT NULL,
    item_type                       VARCHAR2(64)    DEFAULT 'PAYMENT' NOT NULL,
    context_json                    CLOB            NOT NULL,
    context_version                 NUMBER(5)       DEFAULT 1 NOT NULL,
    created_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_payment_exec_context PRIMARY KEY (payment_id)
);

-- 4. DISPATCH_AUDIT_LOG: Observability for dispatch operations
CREATE TABLE DISPATCH_AUDIT_LOG (
    audit_id                        NUMBER          GENERATED ALWAYS AS IDENTITY,
    batch_id                        VARCHAR2(64)    NOT NULL,
    item_type                       VARCHAR2(64)    DEFAULT 'PAYMENT' NOT NULL,
    payment_id                      VARCHAR2(128),
    action                          VARCHAR2(64)    NOT NULL,
    status_before                   VARCHAR2(32),
    status_after                    VARCHAR2(32),
    detail                          VARCHAR2(4000),
    created_at                      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_dispatch_audit_log PRIMARY KEY (audit_id)
);

CREATE INDEX idx_dal_batch ON DISPATCH_AUDIT_LOG (batch_id);
CREATE INDEX idx_dal_payment ON DISPATCH_AUDIT_LOG (payment_id);
CREATE INDEX idx_dal_created ON DISPATCH_AUDIT_LOG (created_at);

-- =============================================================================
-- Seed data: Default PAYMENT dispatch configuration
-- =============================================================================
INSERT INTO EXEC_RATE_CONFIG (
    item_type, enabled, batch_size, dispatch_interval_secs,
    jitter_window_ms, pre_start_buffer_mins, max_dispatch_retries,
    stale_claim_threshold_mins,
    exec_workflow_type, exec_task_queue, description
) VALUES (
    'PAYMENT', 1, 500, 5,
    4000, 30, 5,
    2,
    'PaymentExecWorkflow', 'payment-exec-task-queue',
    'Default payment dispatch configuration'
);
