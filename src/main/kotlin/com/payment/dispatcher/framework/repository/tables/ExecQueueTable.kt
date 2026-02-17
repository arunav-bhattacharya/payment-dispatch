package com.payment.dispatcher.framework.repository.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed DSL definition for PAYMENT_EXEC_QUEUE table.
 * Dispatch queue supporting multiple item types via [itemType].
 *
 * Status lifecycle: READY → CLAIMED → DISPATCHED (terminal — Temporal manages execution from here)
 */
object ExecQueueTable : Table("PAYMENT_EXEC_QUEUE") {
    val paymentId = varchar("payment_id", 128)
    val itemType = varchar("item_type", 64).default("PAYMENT")
    val queueStatus = varchar("queue_status", 32).default("READY")
    val scheduledExecTime = timestamp("scheduled_exec_time")
    val initWorkflowId = varchar("init_workflow_id", 256).nullable()
    val dispatchBatchId = varchar("dispatch_batch_id", 64).nullable()
    val execWorkflowId = varchar("exec_workflow_id", 256).nullable()
    val claimedAt = timestamp("claimed_at").nullable()
    val dispatchedAt = timestamp("dispatched_at").nullable()
    val retryCount = integer("retry_count").default(0)
    val lastError = varchar("last_error", 4000).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(paymentId, name = "pk_payment_exec_queue")
}
