package com.payment.dispatcher.payment.init

import com.payment.dispatcher.payment.context.PaymentContextActivities
import io.quarkiverse.temporal.TemporalWorkflow
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Phase A implementation: runs all initial processing activities,
 * persists the payment as SCHEDULED, builds accumulated context,
 * saves it to Oracle, and enqueues for dispatch.
 *
 * After saveContextAndEnqueue, the workflow returns "ENQUEUED" and COMPLETES.
 * No Workflow.sleep() — the payment waits in Oracle until dispatch time.
 */
@TemporalWorkflow(workers = ["payment-init-worker"])
class PaymentInitWorkflowImpl : PaymentInitWorkflow {

    private val initActivities = Workflow.newActivityStub(
        PaymentInitActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

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

    override fun initializePayment(paymentId: String, requestJson: String): String {
        // ═══ Phase A: Validate and enrich ═══
        val validationResult = initActivities.validatePayment(paymentId, requestJson)
        val enrichmentData = initActivities.enrichPayment(paymentId, requestJson)
        val appliedRules = initActivities.applyRules(paymentId, requestJson)

        // ═══ Persist payment with SCHEDULED status ═══
        initActivities.persistScheduledPayment(paymentId, requestJson)

        // ═══ Build accumulated context ═══
        val context = initActivities.buildContext(
            paymentId = paymentId,
            requestJson = requestJson,
            validationResultJson = validationResult,
            enrichmentDataJson = enrichmentData,
            appliedRulesJson = appliedRules
        )

        // ═══ Determine execution time ═══
        val scheduledExecTime = initActivities.determineExecTime(paymentId, requestJson)

        // ═══ Save context FIRST, then enqueue (ordering matters for failure safety) ═══
        val workflowId = Workflow.getInfo().workflowId
        contextActivities.saveContextAndEnqueue(context, scheduledExecTime, workflowId)

        // Workflow COMPLETES — no more sleeping!
        return "ENQUEUED"
    }
}
