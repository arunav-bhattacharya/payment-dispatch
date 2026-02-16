package com.payment.dispatcher.framework.model

/**
 * Result of dispatching a single item (starting its exec workflow).
 * Collected by the dispatcher for audit logging and metrics.
 *
 * Must be serializable by Temporal (used as activity output).
 */
data class DispatchResult(
    val itemId: String,
    val success: Boolean,
    val alreadyRunning: Boolean = false,
    val error: String? = null,
    val workflowId: String? = null
)
