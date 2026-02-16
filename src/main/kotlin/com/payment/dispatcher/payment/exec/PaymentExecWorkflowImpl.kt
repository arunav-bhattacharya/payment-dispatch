package com.payment.dispatcher.payment.exec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.payment.dispatcher.payment.context.PaymentContextActivities
import com.payment.dispatcher.payment.model.PaymentExecContext
import io.quarkiverse.temporal.TemporalWorkflow
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Phase B implementation: deserializes pre-loaded context, executes payment, handles failures.
 *
 * Context is pre-loaded by the dispatcher at claim time and passed as a JSON string parameter.
 * This eliminates an Oracle round-trip that was previously needed to load the context CLOB.
 *
 * Failure handling:
 * - try/catch wraps all business activities
 * - On failure: marks FAILED in Oracle (increments retry_count, may dead-letter)
 * - Re-throws so Temporal records the workflow failure
 * - If the workflow itself fails to start, stale recovery handles the CLAIMED row
 */
@TemporalWorkflow(workers = ["payment-exec-worker"])
class PaymentExecWorkflowImpl : PaymentExecWorkflow {

    companion object {
        /**
         * Jackson ObjectMapper for deserializing the pre-loaded context JSON.
         * This is deterministic (pure JSON → object mapping) and safe in workflow code.
         */
        private val objectMapper = ObjectMapper().registerModule(kotlinModule())
    }

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

    override fun execute(paymentId: String, contextJson: String) {
        // Deserialize the pre-loaded context (no Oracle round-trip needed)
        val context = objectMapper.readValue(contextJson, PaymentExecContext::class.java)

        try {
            // Execute the actual payment (debit, credit, settlement)
            // Transitions payment status: SCHEDULED → ACCEPTED
            execActivities.executePayment(context)

            // Post-processing (ledger updates, reconciliation)
            execActivities.postProcess(context)

            // Send notifications (email, SMS, webhooks)
            // Transitions payment status: ACCEPTED → PROCESSING
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
