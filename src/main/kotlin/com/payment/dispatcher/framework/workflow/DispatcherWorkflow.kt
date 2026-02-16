package com.payment.dispatcher.framework.workflow

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Generic dispatcher workflow — fires every N seconds via Temporal Schedule.
 * Claims a batch of READY items from Oracle and dispatches them as exec workflows.
 *
 * Parameterized by itemType: the same workflow implementation handles
 * PAYMENT, INVOICE, SETTLEMENT, etc. — each with its own config row
 * in EXEC_RATE_CONFIG and its own Temporal Schedule.
 *
 * Short-lived: starts, does work, completes. No sleeping, no signals.
 */
@WorkflowInterface
interface DispatcherWorkflow {

    /**
     * Execute a single dispatch cycle for the given item type.
     *
     * @param itemType The item type to dispatch (e.g., "PAYMENT", "INVOICE").
     *                 Must match a row in EXEC_RATE_CONFIG.
     */
    @WorkflowMethod
    fun dispatch(itemType: String)
}
