package com.payment.dispatcher.payment.exec

import com.payment.dispatcher.payment.context.PaymentContextActivities
import io.quarkiverse.temporal.TemporalWorkflow
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Phase B implementation: loads context, executes payment, handles failures.
 *
 * Failure handling (P1 #4):
 * - try/catch wraps all business activities
 * - On failure: marks FAILED in Oracle (increments retry_count, may dead-letter)
 * - Re-throws so Temporal records the workflow failure
 * - loadContext failure means status is still CLAIMED → stale recovery handles it
 */
@TemporalWorkflow(workers = ["payment-exec-worker"])
class PaymentExecWorkflowImpl : PaymentExecWorkflow {

    private val contextActivities = Workflow.newActivityStub(
        PaymentContextActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    private val execActivities = Workflow.newActivityStub(
        PaymentExecActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    override fun execute(paymentId: String) {
        // Load context from Oracle CLOB
        val context = contextActivities.loadContext(paymentId)

        try {
            // Execute the actual payment (debit, credit, settlement)
            execActivities.executePayment(context)

            // Post-processing (ledger updates, reconciliation)
            execActivities.postProcess(context)

            // Send notifications (email, SMS, webhooks)
            execActivities.sendNotifications(context)

            // Atomic: mark COMPLETED + delete context CLOB
            contextActivities.completeAndCleanup(paymentId)

        } catch (e: Exception) {
            // Mark FAILED in Oracle (only if status is DISPATCHED)
            // Increments retry_count — may transition to DEAD_LETTER if exhausted
            try {
                contextActivities.markFailed(paymentId, e.message)
            } catch (ignored: Exception) {
                // Best-effort failure marking — stale recovery is the safety net
                // Do not mask the original exception
            }

            // Re-throw so Temporal records the workflow failure
            throw e
        }
    }
}
