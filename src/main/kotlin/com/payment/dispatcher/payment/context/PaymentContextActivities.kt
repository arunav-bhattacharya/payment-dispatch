package com.payment.dispatcher.payment.context

import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Payment-specific context activities.
 * Composes the generic framework services (ExposedContextService, DispatchQueueRepository)
 * with payment-specific types.
 *
 * Used by PaymentInitWorkflow (save + enqueue) and PaymentExecWorkflow (complete, fail).
 *
 * Note: Context loading is no longer an activity — context is pre-loaded at dispatch time
 * and passed directly to the exec workflow as a parameter, eliminating an Oracle round-trip.
 */
@ActivityInterface
interface PaymentContextActivities {

    /**
     * Saves accumulated context to Oracle CLOB and enqueues for dispatch.
     * Called at the end of PaymentInitWorkflow (Phase A).
     *
     * Order matters: saves context FIRST, then enqueues.
     * If enqueue fails, orphaned context is harmless (TTL cleanup).
     * If context save fails, nothing is enqueued — safe failure mode.
     *
     * @param context        Accumulated context from Phase A
     * @param scheduledExecTime ISO-8601 timestamp for scheduled execution
     * @param initWorkflowId  The init workflow ID for tracing
     */
    @ActivityMethod
    fun saveContextAndEnqueue(context: PaymentExecContext, scheduledExecTime: String, initWorkflowId: String?)

    /**
     * Atomically marks COMPLETED + deletes context CLOB.
     * Called on successful execution completion.
     */
    @ActivityMethod
    fun completeAndCleanup(paymentId: String)

    /**
     * Marks the item as FAILED in the dispatch queue.
     * Increments retry_count. If retries exhausted, transitions to DEAD_LETTER.
     * Called by PaymentExecWorkflow on execution failure.
     */
    @ActivityMethod
    fun markFailed(paymentId: String, error: String?)
}
