package com.payment.dispatcher.payment.exec

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Phase B workflow: executes the payment using pre-loaded context,
 * and performs post-processing and notifications.
 *
 * Started by the DispatcherWorkflow with a deterministic workflow ID
 * (exec-payment-{paymentId}) and a startDelay for jitter.
 *
 * Context is pre-loaded at dispatch time (in DispatcherActivitiesImpl)
 * and passed as a JSON string parameter — no Oracle round-trip needed.
 *
 * Short-lived: deserialize context, execute, complete.
 */
@WorkflowInterface
interface PaymentExecWorkflow {

    /**
     * Execute a payment using the previously accumulated context.
     *
     * @param paymentId   The payment ID
     * @param contextJson Pre-loaded execution context as JSON string
     *                    (serialized PaymentExecContext from PAYMENT_EXEC_CONTEXT CLOB)
     */
    @WorkflowMethod
    fun execute(paymentId: String, contextJson: String)
}
