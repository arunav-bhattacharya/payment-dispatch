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
 */
data class ClaimedItem(
    val itemId: String,
    val itemType: String,
    /** ISO-8601 string for Temporal serialization safety */
    val scheduledExecTime: String,
    val initWorkflowId: String?,
    val execWorkflowType: String,
    val execTaskQueue: String
)
