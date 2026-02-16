package com.payment.dispatcher.framework.repository.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed DSL definition for EXEC_RATE_CONFIG table.
 * Dynamic dispatch configuration per item type — updatable at runtime via SQL.
 */
object ExecRateConfigTable : Table("EXEC_RATE_CONFIG") {
    val itemType = varchar("item_type", 64)
    val enabled = integer("enabled").default(1)
    val batchSize = integer("batch_size").default(100)
    val dispatchIntervalSecs = integer("dispatch_interval_secs").default(5)
    val jitterWindowMs = integer("jitter_window_ms").default(2000)
    val preStartBufferMins = integer("pre_start_buffer_mins").default(5)
    val maxDispatchRetries = integer("max_dispatch_retries").default(3)
    val staleClaimThresholdMins = integer("stale_claim_threshold_mins").default(10)
    val maxStaleRecoveryPerCycle = integer("max_stale_recovery_per_cycle").default(50)
    val execWorkflowType = varchar("exec_workflow_type", 256)
    val execTaskQueue = varchar("exec_task_queue", 256)
    val description = varchar("description", 512).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(itemType, name = "pk_exec_rate_config")
}
