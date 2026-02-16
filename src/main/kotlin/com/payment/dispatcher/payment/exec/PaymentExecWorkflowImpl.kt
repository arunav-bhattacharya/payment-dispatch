package com.payment.dispatcher.payment.exec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.payment.dispatcher.framework.workflow.DispatchableWorkflow
import com.payment.dispatcher.payment.model.PaymentExecContext
import io.quarkiverse.temporal.TemporalWorkflow
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Phase B implementation: pure payment business logic.
 *
 * Extends [DispatchableWorkflow] which handles all dispatch lifecycle concerns
 * (marking COMPLETED/FAILED/DEAD_LETTER, context CLOB cleanup) via the
 * template method pattern. This class only contains business logic.
 *
 * Context is pre-loaded by the dispatcher at claim time and passed as a
 * JSON string parameter — no Oracle round-trip needed.
 */
@TemporalWorkflow(workers = ["payment-exec-worker"])
class PaymentExecWorkflowImpl : DispatchableWorkflow(), PaymentExecWorkflow {

    override val itemType: String = "PAYMENT"

    companion object {
        private val objectMapper = ObjectMapper().registerModule(kotlinModule())
    }

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
        executeWithLifecycle(paymentId, contextJson)
    }

    override fun doExecute(paymentId: String, contextJson: String) {
        val context = objectMapper.readValue(contextJson, PaymentExecContext::class.java)

        // Execute the actual payment (debit, credit, settlement)
        // Transitions payment status: SCHEDULED → ACCEPTED
        execActivities.executePayment(context)

        // Post-processing (ledger updates, reconciliation)
        execActivities.postProcess(context)

        // Send notifications (email, SMS, webhooks)
        // Transitions payment status: ACCEPTED → PROCESSING
        execActivities.sendNotifications(context)
    }
}
