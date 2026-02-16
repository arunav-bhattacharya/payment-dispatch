package com.payment.dispatcher.framework.model

/**
 * Dynamic dispatch configuration loaded from EXEC_RATE_CONFIG table.
 * Can be updated at runtime via SQL without redeployment.
 *
 * Must be serializable by Temporal (used as activity input/output).
 */
data class DispatchConfig(
    val itemType: String,
    val enabled: Boolean,
    val batchSize: Int,
    val dispatchIntervalSecs: Int,
    val jitterWindowMs: Int,
    val preStartBufferMins: Int,
    val maxDispatchRetries: Int,
    val staleClaimThresholdMins: Int,
    val maxStaleRecoveryPerCycle: Int,
    val execWorkflowType: String,
    val execTaskQueue: String
)
