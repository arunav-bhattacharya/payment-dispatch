# Rate-Limited Payment Dispatch System

A two-phase, rate-limited payment processing system built on **Quarkus**, **Temporal**, and **Oracle**. Designed to handle 500K–1M+ daily payments that converge on a single execution window (e.g., 16:00 MST) without overwhelming the Temporal Postgres database.

## Problem

When hundreds of thousands of payments are scheduled for the same execution time, using `Workflow.sleep()` per payment creates that many sleeping workflows in Temporal. This saturates the Temporal Postgres DB (`db.r8g.8xlarge` Aurora) with workflow state, timers, and history rows — causing elevated latency, connection exhaustion, and operational instability.

## Solution

Split the single long-lived workflow into **two short-lived workflows** connected by an **Oracle-based dispatch queue**:

- **Phase A** — Validate, enrich, apply rules, calculate fees, persist context, enqueue, and **complete immediately** (no sleep).
- **Phase B** — A Temporal Schedule fires a dispatcher every N seconds. The dispatcher claims batches from Oracle using `FOR UPDATE SKIP LOCKED`, then starts execution workflows with `startDelay()` jitter.

This reduces Temporal DB pressure by **~200×** (from 500K sleeping workflows to ~500 concurrent short-lived ones).

---

## Architecture

```mermaid
graph TB
    subgraph "Phase A — Payment Initialization"
        API["REST API / Kafka"] -->|PaymentRequest| INIT_WF["PaymentInitWorkflow"]
        INIT_WF --> VALIDATE["Validate Payment"]
        VALIDATE --> ENRICH["Enrich Payment"]
        ENRICH --> RULES["Apply Rules"]
        RULES --> FEES["Calculate Fees"]
        FEES --> BUILD_CTX["Build Context"]
        BUILD_CTX --> SAVE_CTX["Save Context (CLOB)"]
        SAVE_CTX --> ENQUEUE["Enqueue (READY)"]
        ENQUEUE --> COMPLETE_A["Workflow Completes ✓"]
    end

    subgraph "Oracle Dispatch Queue"
        ENQUEUE --> ORACLE_Q[("PAYMENT_EXEC_QUEUE\n(status: READY)")]
        SAVE_CTX --> ORACLE_CTX[("PAYMENT_EXEC_CONTEXT\n(JSON CLOB)")]
    end

    subgraph "Phase B — Dispatch & Execution"
        SCHEDULE["Temporal Schedule\n(every 5s)"] -->|fires| DISP_WF["DispatcherWorkflow"]
        DISP_WF --> READ_CFG["Read Config"]
        READ_CFG --> STALE["Recover Stale Claims"]
        STALE --> CLAIM["Claim Batch\n(FOR UPDATE SKIP LOCKED)"]
        CLAIM --> ORACLE_Q
        CLAIM --> DISPATCH["Dispatch Batch\n(start exec workflows)"]
        DISPATCH -->|"startDelay(jitter)"| EXEC_WF["PaymentExecWorkflow"]
        EXEC_WF --> LOAD_CTX["Load Context"]
        LOAD_CTX --> ORACLE_CTX
        LOAD_CTX --> EXECUTE["Execute Payment"]
        EXECUTE --> POST["Post-Process"]
        POST --> NOTIFY["Send Notifications"]
        NOTIFY --> CLEANUP["Mark COMPLETED\n+ Delete Context"]
    end

    subgraph "Observability"
        DISP_WF --> AUDIT[("DISPATCH_AUDIT_LOG")]
        DISP_WF --> METRICS["Prometheus Metrics\n(/q/metrics)"]
    end

    style ORACLE_Q fill:#f9e2af,stroke:#e6a817
    style ORACLE_CTX fill:#f9e2af,stroke:#e6a817
    style AUDIT fill:#f9e2af,stroke:#e6a817
    style SCHEDULE fill:#89b4fa,stroke:#1e66f5
    style COMPLETE_A fill:#a6e3a1,stroke:#40a02b
```

---

## Dispatch Cycle — Sequence Diagram

```mermaid
sequenceDiagram
    participant S as Temporal Schedule
    participant DW as DispatcherWorkflow
    participant DA as DispatcherActivities
    participant OQ as Oracle Queue
    participant OC as Oracle Config
    participant T as Temporal Server
    participant EW as PaymentExecWorkflow

    S->>DW: dispatch("PAYMENT")

    Note over DW,DA: Step 1 — Read Config
    DW->>DA: readDispatchConfig("PAYMENT")
    DA->>OC: SELECT * FROM EXEC_RATE_CONFIG
    OC-->>DA: DispatchConfig (batchSize=500, jitter=4000ms, ...)
    DA-->>DW: config

    alt config.enabled == false
        DW-->>S: return (kill switch active)
    end

    Note over DW,DA: Step 2 — Recover Stale Claims
    DW->>DA: recoverStaleClaims(config)
    DA->>OQ: SELECT CLAIMED rows older than 2 mins
    OQ-->>DA: List<StaleClaim>
    loop For each stale claim
        DA->>T: describe(execWorkflowId)
        alt WorkflowNotFoundException
            DA->>OQ: UPDATE status → READY (retry_count++)
        else Workflow exists
            Note over DA: Skip (conservative)
        end
    end
    DA-->>DW: recoveredCount

    Note over DW,DA: Step 3 — Claim Batch
    DW->>DA: claimBatch(config)
    DA->>OQ: SELECT ... FOR UPDATE SKIP LOCKED (up to 500 rows)
    DA->>OQ: UPDATE locked rows → CLAIMED (dispatch_batch_id)
    OQ-->>DA: ClaimedBatch (N items)
    DA-->>DW: batch

    alt batch.items.isEmpty()
        DW-->>S: return (nothing to dispatch)
    end

    Note over DW,DA: Step 4 — Dispatch Batch
    DW->>DA: dispatchBatch(batch, config)
    loop For each item in batch
        DA->>DA: jitterDelay = random(0, 4000ms)
        DA->>DA: workflowId = "exec-payment-{paymentId}"
        DA->>T: WorkflowClient.start(workflowId, startDelay=jitterDelay)
        alt Started successfully
            DA->>OQ: UPDATE status → DISPATCHED
        else WorkflowExecutionAlreadyStarted
            Note over DA: Treat as success (dedup)
            DA->>OQ: UPDATE status → DISPATCHED
        else Start failed
            DA->>OQ: UPDATE status → READY (retry_count++)
        end
    end
    DA-->>DW: List<DispatchResult>

    Note over DW,DA: Step 5 — Record Results
    DW->>DA: recordResults(batchId, results, config)
    DA->>OQ: INSERT audit log (BATCH_COMPLETE summary)

    DW-->>S: done (workflow completes)

    Note over T,EW: After startDelay elapses...
    T->>EW: execute(paymentId)
    EW->>OQ: Load context from CLOB
    EW->>EW: executePayment → postProcess → sendNotifications
    alt Success
        EW->>OQ: DISPATCHED → COMPLETED, delete context
    else Failure
        EW->>OQ: DISPATCHED → FAILED (or DEAD_LETTER)
    end
```

---

## Payment Execution — Sequence Diagram

```mermaid
sequenceDiagram
    participant T as Temporal Server
    participant EW as PaymentExecWorkflow
    participant CA as ContextActivities
    participant EA as ExecActivities
    participant OQ as Oracle Queue
    participant OC as Oracle Context

    Note over T,EW: Exec workflow starts after startDelay jitter

    T->>EW: execute(paymentId)

    Note over EW,CA: Step 1 — Load Context
    EW->>CA: loadContext(paymentId)
    CA->>OC: SELECT context_json FROM PAYMENT_EXEC_CONTEXT
    OC-->>CA: JSON CLOB
    CA->>CA: Deserialize → PaymentExecContext
    CA-->>EW: context (validation, enrichment, rules, fees, FX)

    Note over EW,EA: Step 2 — Execute Payment
    EW->>EA: executePayment(context)
    EA->>EA: Debit source account
    EA->>EA: Credit destination account
    EA->>EA: Settlement processing
    EA-->>EW: success

    Note over EW,EA: Step 3 — Post-Process
    EW->>EA: postProcess(context)
    EA->>EA: Ledger entries
    EA->>EA: Reconciliation records
    EA->>EA: Regulatory reporting
    EA-->>EW: success

    Note over EW,EA: Step 4 — Notifications
    EW->>EA: sendNotifications(context)
    EA->>EA: Email / SMS / Webhooks
    EA-->>EW: success

    Note over EW,CA: Step 5 — Completion
    EW->>CA: completeAndCleanup(paymentId)
    CA->>OQ: UPDATE status → COMPLETED
    CA->>OC: DELETE context CLOB
    CA-->>EW: done

    alt Failure at any step
        EW->>CA: markFailed(paymentId, error)
        CA->>OQ: Read retry_count
        alt retry_count + 1 < maxRetries
            CA->>OQ: UPDATE status → FAILED (retry_count++)
            Note over CA: Will be retried
        else retry_count + 1 >= maxRetries
            CA->>OQ: UPDATE status → DEAD_LETTER
            Note over CA: Manual investigation required
        end
        EW->>EW: re-throw exception
    end
```

---

## Queue Status — State Diagram

```mermaid
stateDiagram-v2
    [*] --> READY : Phase A enqueues payment

    READY --> CLAIMED : Dispatcher claims batch\n(FOR UPDATE SKIP LOCKED)

    CLAIMED --> DISPATCHED : Exec workflow started\n(WorkflowClient.start)
    CLAIMED --> READY : Dispatch failed\n(retry_count++)
    CLAIMED --> READY : Stale recovery\n(claimed_at > threshold\n& workflow not found)

    DISPATCHED --> COMPLETED : Execution succeeded\n(context deleted)
    DISPATCHED --> FAILED : Execution failed\n(retry_count++)

    FAILED --> DEAD_LETTER : retry_count >= maxRetries\n(terminal — manual action)

    COMPLETED --> [*]
    DEAD_LETTER --> [*]

    note right of READY
        Default initial state.
        Eligible for next dispatch cycle.
    end note

    note right of CLAIMED
        Locked by a dispatcher instance.
        Cleared by stale recovery if
        dispatcher crashes.
    end note

    note right of DISPATCHED
        Exec workflow confirmed running
        in Temporal. Deterministic
        workflow ID prevents duplicates.
    end note

    note right of DEAD_LETTER
        Terminal state. Retries exhausted.
        Requires manual investigation.
        Alert: dispatch.dead.letter > 0
    end note
```

---

## Phase A — Payment Initialization State Diagram

```mermaid
stateDiagram-v2
    [*] --> Validating : PaymentInitWorkflow started

    Validating --> Enriching : Validation passed
    Validating --> Failed_Init : Validation failed

    Enriching --> ApplyingRules : Enrichment complete
    Enriching --> Failed_Init : Enrichment error

    ApplyingRules --> CalculatingFees : Rules applied
    ApplyingRules --> Failed_Init : Rule rejection

    CalculatingFees --> BuildingContext : Fees calculated
    CalculatingFees --> Failed_Init : Fee calculation error

    BuildingContext --> SavingContext : Context assembled
    SavingContext --> Enqueueing : Context saved to CLOB
    SavingContext --> Failed_Init : Save failed (no enqueue)

    Enqueueing --> Completed_A : Enqueued (READY)
    Enqueueing --> OrphanedContext : Enqueue failed\n(context exists, harmless)

    Completed_A --> [*] : Workflow completes immediately

    note right of SavingContext
        Order matters:
        1. Save context FIRST
        2. Then enqueue
        If save fails → nothing enqueued (safe)
        If enqueue fails → orphaned context (TTL cleanup)
    end note

    note right of Completed_A
        No Workflow.sleep()
        Payment waits in Oracle
        READY status until
        dispatcher picks it up
    end note
```

---

## Deduplication Protection Layers

The system implements four layers of duplicate dispatch prevention:

```mermaid
graph TB
    subgraph "Layer 1 — Temporal Schedule"
        L1["ScheduleOverlapPolicy.SKIP\nPrevents concurrent dispatcher cycles"]
    end

    subgraph "Layer 2 — Oracle Locking"
        L2["FOR UPDATE SKIP LOCKED\nEach dispatcher claims disjoint batch\nNo two dispatchers claim same item"]
    end

    subgraph "Layer 3 — Deterministic Workflow ID"
        L3["workflowId = exec-{type}-{itemId}\nTemporal rejects duplicate starts\n(WorkflowExecutionAlreadyStarted)"]
    end

    subgraph "Layer 4 — Status Guards"
        L4["Queue status transitions are\nconditional (WHERE status = X)\nPrevents out-of-order transitions"]
    end

    L1 --> L2
    L2 --> L3
    L3 --> L4

    style L1 fill:#89b4fa,stroke:#1e66f5
    style L2 fill:#f9e2af,stroke:#e6a817
    style L3 fill:#a6e3a1,stroke:#40a02b
    style L4 fill:#f5c2e7,stroke:#ea76cb
```

| Layer | Mechanism | Prevents |
|-------|-----------|----------|
| **Schedule SKIP** | `ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP` | Concurrent dispatcher workflows |
| **SKIP LOCKED** | `SELECT ... FOR UPDATE SKIP LOCKED` | Two dispatchers claiming same row |
| **Deterministic ID** | `exec-{itemType}-{itemId}` | Duplicate exec workflow starts |
| **Status Guards** | `WHERE queue_status = 'CLAIMED'` | Out-of-order status transitions |

---

## Oracle Schema

```mermaid
erDiagram
    EXEC_RATE_CONFIG {
        VARCHAR2 item_type PK "PAYMENT, INVOICE, etc."
        NUMBER enabled "Kill switch (0/1)"
        NUMBER batch_size "Items per cycle"
        NUMBER dispatch_interval_secs "Schedule interval"
        NUMBER jitter_window_ms "startDelay variance"
        NUMBER pre_start_buffer_mins "How far ahead to query"
        NUMBER max_dispatch_retries "Retries before DEAD_LETTER"
        NUMBER stale_claim_threshold_mins "Stale claim detection"
        NUMBER max_stale_recovery_per_cycle "Recovery cap"
        VARCHAR2 exec_workflow_type "e.g. PaymentExecWorkflow"
        VARCHAR2 exec_task_queue "e.g. payment-exec-task-queue"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    PAYMENT_EXEC_QUEUE {
        VARCHAR2 payment_id PK "Unique item ID"
        VARCHAR2 item_type "Type discriminator"
        VARCHAR2 queue_status "READY/CLAIMED/DISPATCHED/..."
        TIMESTAMP scheduled_exec_time "When to dispatch"
        VARCHAR2 init_workflow_id "Phase A workflow ID"
        VARCHAR2 dispatch_batch_id "BATCH-xxxx"
        VARCHAR2 exec_workflow_id "Phase B workflow ID"
        TIMESTAMP claimed_at
        TIMESTAMP dispatched_at
        TIMESTAMP completed_at
        NUMBER retry_count "Incremented on failure"
        VARCHAR2 last_error "Error from last failure"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    PAYMENT_EXEC_CONTEXT {
        VARCHAR2 payment_id PK "Same as queue"
        VARCHAR2 item_type "Type discriminator"
        CLOB context_json "Phase A results (JSON)"
        NUMBER context_version "Schema version"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    DISPATCH_AUDIT_LOG {
        NUMBER audit_id PK "Auto-increment"
        VARCHAR2 batch_id "BATCH-xxxx or STALE-RECOVERY"
        VARCHAR2 item_type "Type discriminator"
        VARCHAR2 payment_id "Item ID"
        VARCHAR2 action "DISPATCHED/FAILED/STALE_RECOVERY/..."
        VARCHAR2 status_before
        VARCHAR2 status_after
        VARCHAR2 detail "Free-form context"
        TIMESTAMP created_at
    }

    EXEC_RATE_CONFIG ||--o{ PAYMENT_EXEC_QUEUE : "item_type (logical)"
    PAYMENT_EXEC_QUEUE ||--o| PAYMENT_EXEC_CONTEXT : "payment_id (logical)"
    PAYMENT_EXEC_QUEUE ||--o{ DISPATCH_AUDIT_LOG : "payment_id (logical)"
```

> **Note:** No foreign keys are used. Referential integrity is enforced at the application level. This avoids FK lock contention under high-throughput batch operations.

### Key Indexes

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_peq_dispatch` | `(item_type, queue_status, scheduled_exec_time, retry_count)` | `claimBatch` — high-frequency dispatcher query |
| `idx_peq_stale_claims` | `(item_type, queue_status, claimed_at)` | Stale recovery query |
| `idx_peq_batch` | `(dispatch_batch_id)` | Post-claim batch lookup |

---

## Project Structure

```
payment-dispatch/
├── build.gradle.kts                          # Gradle build (Quarkus + Temporal + Exposed)
├── settings.gradle.kts                       # Project settings
├── gradle.properties                         # Version pinning
│
└── src/main/
    ├── resources/
    │   ├── application.yaml                  # Quarkus config (Temporal, Oracle, metrics)
    │   └── db/migration/
    │       └── V1__create_dispatch_tables.sql # Oracle DDL (4 tables, indexes, seed data)
    │
    └── kotlin/com/payment/dispatcher/
        │
        ├── config/
        │   ├── AppConfig.kt                  # @ConfigMapping for dispatch settings
        │   └── DispatchScheduleInitializer.kt # Creates Temporal Schedule on startup
        │
        ├── framework/                        # ── Generic Dispatch Infrastructure ──
        │   ├── model/
        │   │   ├── QueueStatus.kt            # READY/CLAIMED/DISPATCHED/COMPLETED/FAILED/DEAD_LETTER
        │   │   ├── DispatchConfig.kt         # Runtime config loaded from EXEC_RATE_CONFIG
        │   │   ├── ClaimedBatch.kt           # Batch claim result (batchId + items)
        │   │   └── DispatchResult.kt         # Per-item dispatch result
        │   │
        │   ├── repository/
        │   │   ├── tables/
        │   │   │   ├── ExecRateConfigTable.kt    # Exposed table: EXEC_RATE_CONFIG
        │   │   │   ├── ExecQueueTable.kt         # Exposed table: PAYMENT_EXEC_QUEUE
        │   │   │   ├── ExecContextTable.kt       # Exposed table: PAYMENT_EXEC_CONTEXT
        │   │   │   └── DispatchAuditLogTable.kt  # Exposed table: DISPATCH_AUDIT_LOG
        │   │   │
        │   │   ├── DispatchQueueRepository.kt    # Core queue ops (claim, status, stale recovery)
        │   │   ├── DispatchConfigRepository.kt   # Config reader
        │   │   └── DispatchAuditRepository.kt    # Insert-only audit log
        │   │
        │   ├── context/
        │   │   ├── ExecutionContextService.kt    # Generic context interface
        │   │   └── ExposedContextService.kt      # Exposed + Jackson implementation
        │   │
        │   ├── activity/
        │   │   ├── DispatcherActivities.kt       # @ActivityInterface (5 methods)
        │   │   └── DispatcherActivitiesImpl.kt   # Core dispatch logic
        │   │
        │   ├── workflow/
        │   │   ├── DispatcherWorkflow.kt         # @WorkflowInterface
        │   │   └── DispatcherWorkflowImpl.kt     # 5-step dispatch cycle
        │   │
        │   ├── schedule/
        │   │   └── DispatchScheduleSetup.kt      # Temporal Schedule creation
        │   │
        │   ├── config/
        │   │   └── ExposedDatabaseConfig.kt      # Exposed ↔ Agroal bridge
        │   │
        │   └── metrics/
        │       └── DispatchMetrics.kt            # Micrometer counters + timers
        │
        └── payment/                          # ── Payment-Specific Implementation ──
            ├── model/
            │   ├── PaymentRequest.kt             # Inbound DTO
            │   └── PaymentExecContext.kt          # Phase A accumulated context
            │
            ├── context/
            │   ├── PaymentContextActivities.kt   # @ActivityInterface (bridge)
            │   └── PaymentContextActivitiesImpl.kt # Composes generic services
            │
            ├── init/                             # Phase A
            │   ├── PaymentInitWorkflow.kt        # @WorkflowInterface
            │   ├── PaymentInitWorkflowImpl.kt    # Validate → Enrich → Rules → Fees → Enqueue
            │   ├── PaymentInitActivities.kt      # @ActivityInterface
            │   └── PaymentInitActivitiesImpl.kt  # Business logic stubs
            │
            └── exec/                             # Phase B
                ├── PaymentExecWorkflow.kt        # @WorkflowInterface
                ├── PaymentExecWorkflowImpl.kt    # Load context → Execute → Complete/Fail
                ├── PaymentExecActivities.kt      # @ActivityInterface
                └── PaymentExecActivitiesImpl.kt  # Business logic stubs
```

---

## Key Design Decisions

### `startDelay()` Instead of `Workflow.sleep()`

```mermaid
graph LR
    subgraph "Before — Workflow.sleep()"
        A1["500K Workflows Created"] --> A2["500K Sleeping\n(in Temporal DB)"] --> A3["All wake at 16:00\n(thundering herd)"]
    end

    subgraph "After — startDelay()"
        B1["Dispatcher claims\n500 items/cycle"] --> B2["WorkflowClient.start()\nwith startDelay(jitter)"] --> B3["~500 workflows\nstaggered over\njitter window"]
    end

    style A2 fill:#f38ba8,stroke:#d20f39
    style B3 fill:#a6e3a1,stroke:#40a02b
```

- **No sleeping workflows** — payments wait in Oracle `READY` status, not in Temporal
- **Controlled throughput** — dispatcher starts ~500 workflows per 5-second cycle
- **Jitter** — `startDelay(random(0, 4000ms))` spreads execution starts, preventing thundering herd
- **~200× reduction** in Temporal DB pressure

### Oracle `FOR UPDATE SKIP LOCKED`

Raw JDBC is used for the batch claim operation because Kotlin Exposed DSL only provides `ForUpdateOption.PostgreSQL` — there is no Oracle-specific variant.

```sql
SELECT payment_id FROM PAYMENT_EXEC_QUEUE
WHERE item_type = ?
  AND queue_status = 'READY'
  AND scheduled_exec_time <= ?
  AND retry_count < ?
ORDER BY scheduled_exec_time ASC
FETCH FIRST ? ROWS ONLY
FOR UPDATE SKIP LOCKED
```

- **Contention-free** — multiple dispatcher instances can run concurrently
- **Non-blocking** — `SKIP LOCKED` skips rows locked by other transactions
- **Atomic** — SELECT + UPDATE within the same JDBC transaction

### Insert-First Context Persistence

Instead of Exposed's `upsert()` (which generates Oracle `MERGE`), context saves use `insert()` with duplicate-key fallback:

```kotlin
try {
    ExecContextTable.insert { ... }
} catch (e: ExposedSQLException) {
    if (isUniqueViolation) {
        ExecContextTable.update({ ... }) { ... }
    } else throw e
}
```

- **Lower overhead** — avoids MERGE on every save
- **Idempotent** — safe on Temporal activity retries
- **Common path optimized** — first insert is a simple INSERT (majority case)

### Composition Over Inheritance

The framework layer is generic and reusable. Payment-specific implementations compose generic services:

```mermaid
graph TB
    subgraph "Framework (Generic)"
        ECS["ExposedContextService&lt;T&gt;"]
        DQR["DispatchQueueRepository"]
        DCR["DispatchConfigRepository"]
        DM["DispatchMetrics"]
    end

    subgraph "Payment Domain (Specific)"
        PCA["PaymentContextActivitiesImpl"]
    end

    PCA -->|"composes"| ECS
    PCA -->|"composes"| DQR
    PCA -->|"composes"| DCR
    PCA -->|"composes"| DM

    ECS -.->|"T = PaymentExecContext"| PCA

    style ECS fill:#89b4fa,stroke:#1e66f5
    style DQR fill:#89b4fa,stroke:#1e66f5
    style DCR fill:#89b4fa,stroke:#1e66f5
    style DM fill:#89b4fa,stroke:#1e66f5
    style PCA fill:#a6e3a1,stroke:#40a02b
```

To add a new domain (e.g., invoices):
1. Insert config row in `EXEC_RATE_CONFIG` (`item_type='INVOICE'`)
2. Create `InvoiceExecContext`, `InvoiceInitWorkflow`, `InvoiceExecWorkflow`
3. Create `InvoiceContextActivitiesImpl` composing the same generic services
4. No changes to the framework layer

---

## Failure Recovery

```mermaid
flowchart TD
    F1["Dispatcher crashes\nafter claiming batch"] -->|"Items stuck in CLAIMED"| R1["Stale Recovery\n(next cycle detects\nclaimed_at > 2 min)"]
    R1 -->|"Check Temporal:\nworkflow not found"| R1A["Reset to READY\n(retry_count++)"]
    R1 -->|"Check Temporal:\nworkflow exists"| R1B["Skip\n(conservative)"]

    F2["WorkflowClient.start()\nfails for an item"] --> R2["Reset to READY\nimmediately\n(retry_count++)"]

    F3["Exec workflow fails\n(exception thrown)"] --> R3{"retry_count + 1\n>= maxRetries?"}
    R3 -->|No| R3A["Mark FAILED\n(retry next cycle)"]
    R3 -->|Yes| R3B["Mark DEAD_LETTER\n(terminal — alert fires)"]

    F4["Context load fails\n(exec workflow)"] -->|"Status stays CLAIMED"| R1

    style R1A fill:#a6e3a1,stroke:#40a02b
    style R2 fill:#a6e3a1,stroke:#40a02b
    style R3A fill:#f9e2af,stroke:#e6a817
    style R3B fill:#f38ba8,stroke:#d20f39
    style R1B fill:#89b4fa,stroke:#1e66f5
```

---

## Temporal Workers

Three dedicated workers with isolated task queues:

| Worker | Task Queue | Workflows | Activities | Concurrency |
|--------|-----------|-----------|------------|-------------|
| **dispatch-worker** | `dispatch-task-queue` | DispatcherWorkflow | DispatcherActivities | 5 WF / 10 Act |
| **payment-init-worker** | `payment-init-task-queue` | PaymentInitWorkflow | PaymentInitActivities | 100 WF / 200 Act |
| **payment-exec-worker** | `payment-exec-task-queue` | PaymentExecWorkflow | PaymentExecActivities, PaymentContextActivities | 100 WF / 200 Act |

---

## Metrics & Alerting

Exported via Micrometer to Prometheus at `/q/metrics`.

### Counters

| Metric | Description |
|--------|-------------|
| `dispatch.batch.claimed` | Total items claimed across all batches |
| `dispatch.workflow.started` | Exec workflows successfully started |
| `dispatch.workflow.start.failures` | Exec workflow start failures |
| `dispatch.stale.recovered` | Stale claims recovered to READY |
| `dispatch.dead.letter` | Items moved to DEAD_LETTER |

### Timers

| Metric | Description |
|--------|-------------|
| `dispatch.cycle.duration` | Full dispatch cycle duration (seconds) |

### Recommended Alerts

| Alert | Condition | Severity |
|-------|-----------|----------|
| Queue depth high | `dispatch.queue.depth{status="READY"} > 10000` for 5m | Warning |
| Dead letters appearing | `rate(dispatch.dead.letter[5m]) > 0` for 1m | Critical |
| Stale recovery elevated | `rate(dispatch.stale.recovered[5m]) > 1` for 5m | Warning |
| Dispatch failures spike | `rate(dispatch.workflow.start.failures[5m]) > 5` for 2m | Critical |

---

## Runtime Configuration

All dispatch parameters are stored in `EXEC_RATE_CONFIG` and read each cycle — **no redeploy needed**.

### Tuning batch size

```sql
UPDATE EXEC_RATE_CONFIG SET batch_size = 1000 WHERE item_type = 'PAYMENT';
-- Takes effect on next dispatch cycle (within 5 seconds)
```

### Kill switch

```sql
UPDATE EXEC_RATE_CONFIG SET enabled = 0 WHERE item_type = 'PAYMENT';
-- Dispatcher reads config, sees enabled=0, returns immediately
-- Items remain in READY status (not lost)
```

### Adjusting jitter window

```sql
UPDATE EXEC_RATE_CONFIG SET jitter_window_ms = 8000 WHERE item_type = 'PAYMENT';
-- Exec workflows will now spread starts over 0-8 seconds instead of 0-4 seconds
```

---

## Capacity Math

| Metric | Before (sleep-based) | After (dispatch queue) |
|--------|---------------------|----------------------|
| Sleeping workflows | 500,000 | 0 |
| Concurrent workflows | 500,000 | ~500 per cycle |
| Temporal DB rows (active) | ~2,000,000 | ~10,000 |
| DB pressure factor | 1× | ~0.005× (200× reduction) |
| Dispatch latency | All at once (thundering herd) | Staggered over jitter window |

---

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **Kotlin** | 2.1.0 | Language (JVM 21) |
| **Quarkus** | 3.29.4 | Application framework (CDI, REST, health, config) |
| **Temporal SDK** | 1.31.0 | Workflow orchestration |
| **Quarkiverse Temporal** | 0.2.2 | Quarkus-Temporal integration |
| **Kotlin Exposed** | 0.61.0 | Type-safe SQL DSL (Oracle) |
| **Oracle** | — | Dispatch queue, context persistence, config, audit |
| **Agroal** | (Quarkus-managed) | Oracle connection pooling (5–20 connections) |
| **Micrometer + Prometheus** | (Quarkus-managed) | Metrics export |
| **Jackson** | (Quarkus-managed) | JSON serialization (Kotlin module) |

---

## Getting Started

### Prerequisites

- JDK 21+
- Oracle database (or Oracle XE for local dev)
- Temporal Server (local or remote)
- Gradle 8.11.1

### Build

```bash
./gradlew build
```

### Run Database Migration

Execute the DDL script against your Oracle instance:

```bash
sqlplus dispatch_user/dispatch_pass@//localhost:1521/XEPDB1 @src/main/resources/db/migration/V1__create_dispatch_tables.sql
```

### Run

```bash
./gradlew quarkusDev
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TEMPORAL_TARGET` | `localhost:7233` | Temporal gRPC endpoint |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |
| `ORACLE_USER` | `dispatch_user` | Oracle username |
| `ORACLE_PASSWORD` | `dispatch_pass` | Oracle password |
| `ORACLE_JDBC_URL` | `jdbc:oracle:thin:@//localhost:1521/XEPDB1` | Oracle JDBC URL |
