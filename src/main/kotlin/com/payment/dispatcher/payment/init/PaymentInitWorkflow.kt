package com.payment.dispatcher.payment.init

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Phase A workflow: validates, enriches, applies rules, calculates fees,
 * then saves accumulated context to Oracle and enqueues for dispatch.
 *
 * After enqueue, the workflow COMPLETES — no Workflow.sleep().
 * The payment sits in PAYMENT_EXEC_QUEUE with status READY until
 * the DispatcherWorkflow picks it up at the scheduled execution time.
 */
@WorkflowInterface
interface PaymentInitWorkflow {

    /**
     * Initialize a payment: run all Phase A activities and enqueue for execution.
     *
     * @param paymentId   Unique payment identifier
     * @param requestJson JSON-serialized PaymentRequest
     * @return Status string (e.g., "ENQUEUED")
     */
    @WorkflowMethod
    fun initializePayment(paymentId: String, requestJson: String): String
}
