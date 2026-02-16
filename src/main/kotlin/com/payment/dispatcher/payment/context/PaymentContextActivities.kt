package com.payment.dispatcher.payment.context

import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Payment-specific context activities — Phase A only.
 *
 * Handles saving the accumulated execution context and enqueueing for dispatch.
 * Called at the end of PaymentInitWorkflow after all Phase A work is done.
 *
 * Dispatch lifecycle concerns (complete, fail, cleanup) are handled by
 * [DispatchableWorkflow] + [DispatcherActivities] at the framework level,
 * keeping this interface focused on the business domain.
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
     * @param context         Accumulated context from Phase A
     * @param scheduledExecTime ISO-8601 timestamp for scheduled execution
     * @param initWorkflowId  The init workflow ID for tracing
     */
    @ActivityMethod
    fun saveContextAndEnqueue(context: PaymentExecContext, scheduledExecTime: String, initWorkflowId: String?)
}
