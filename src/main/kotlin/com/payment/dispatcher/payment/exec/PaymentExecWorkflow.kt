package com.payment.dispatcher.payment.exec

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Phase B workflow: loads context from Oracle, executes the payment,
 * and performs post-processing and notifications.
 *
 * Started by the DispatcherWorkflow with a deterministic workflow ID
 * (exec-payment-{paymentId}) and a startDelay for jitter.
 *
 * Short-lived: loads context, executes, completes.
 */
@WorkflowInterface
interface PaymentExecWorkflow {

    /**
     * Execute a payment using the previously accumulated context.
     *
     * @param paymentId The payment ID — used to load context from Oracle
     */
    @WorkflowMethod
    fun execute(paymentId: String)
}
