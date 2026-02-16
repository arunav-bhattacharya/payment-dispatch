package com.payment.dispatcher.framework.model

/**
 * Result of a batch claim operation.
 * Contains the batch ID and list of claimed items ready for dispatch.
 *
 * Must be serializable by Temporal (used as activity output).
 */
data class ClaimedBatch(
    val batchId: String,
    val itemType: String,
    val items: List<ClaimedItem>
)

/**
 * A single item claimed from the dispatch queue.
 * All temporal types use String for serialization compatibility.
 *
 * The [contextJson] field carries the pre-loaded execution context as a raw JSON string.
 * This is loaded from PAYMENT_EXEC_CONTEXT at claim time (joined during batch claim)
 * so the exec workflow receives it directly — no separate Oracle round-trip needed.
 */
data class ClaimedItem(
    val itemId: String,
    val itemType: String,
    /** ISO-8601 string for Temporal serialization safety */
    val scheduledExecTime: String,
    val initWorkflowId: String?,
    val execWorkflowType: String,
    val execTaskQueue: String,
    /** Pre-loaded execution context as raw JSON string (from PAYMENT_EXEC_CONTEXT CLOB) */
    val contextJson: String
)
