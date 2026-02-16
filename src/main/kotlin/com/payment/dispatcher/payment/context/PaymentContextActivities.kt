package com.payment.dispatcher.payment.context

import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Payment-specific context activities.
 * Composes the generic framework services (ExposedContextService, DispatchQueueRepository)
 * with payment-specific types.
 *
 * Used by both PaymentInitWorkflow (save + enqueue) and PaymentExecWorkflow (load, complete, fail).
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
     * Loads context from Oracle CLOB for execution.
     * Called as the first activity of PaymentExecWorkflow (Phase B).
     *
     * @param paymentId The payment ID to load context for
     * @return Deserialized PaymentExecContext
     * @throws IllegalStateException if context not found
     */
    @ActivityMethod
    fun loadContext(paymentId: String): PaymentExecContext

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
