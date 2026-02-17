package com.payment.dispatcher.framework.activity

import com.payment.dispatcher.framework.model.ClaimedBatch
import com.payment.dispatcher.framework.model.DispatchConfig
import com.payment.dispatcher.framework.model.DispatchResult
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Generic dispatcher activities — reusable across all item types.
 * The dispatcher workflow calls these in sequence each cycle.
 *
 * All activities use maxAttempts=1 (no auto-retry).
 * A failed dispatch cycle is picked up by the next scheduled cycle.
 */
@ActivityInterface
interface DispatcherActivities {

    /**
     * Reads dispatch configuration from Oracle.
     * Includes kill switch check (config.enabled).
     */
    @ActivityMethod
    fun readDispatchConfig(itemType: String): DispatchConfig

    /**
     * Claims a batch of dispatchable items using FOR UPDATE SKIP LOCKED.
     * Atomic, contention-free batch claim.
     *
     * The claim query uses an OR predicate to pick up both:
     * - READY items whose scheduled_exec_time has arrived
     * - Stale CLAIMED items whose claimed_at exceeds the threshold
     *
     * This unified approach eliminates the need for a separate stale recovery step.
     * Stale items are simply re-claimed and re-dispatched; the deterministic workflow
     * ID + WorkflowExecutionAlreadyStarted handler ensures correctness.
     */
    @ActivityMethod
    fun claimBatch(config: DispatchConfig): ClaimedBatch

    /**
     * Dispatches the entire batch by starting exec workflows.
     * Uses WorkflowOptions.setStartDelay() for jitter.
     * Single activity for the whole batch (not per-item fan-out).
     */
    @ActivityMethod
    fun dispatchBatch(batch: ClaimedBatch, config: DispatchConfig): List<DispatchResult>

    /**
     * Records dispatch results to the audit log.
     */
    @ActivityMethod
    fun recordResults(batchId: String, results: List<DispatchResult>, config: DispatchConfig)
}
