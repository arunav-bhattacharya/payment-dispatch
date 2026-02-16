package com.payment.dispatcher.payment.exec

import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Phase B execution activities: the actual payment processing.
 * These use the accumulated context from Phase A — no need to re-validate,
 * re-enrich, etc.
 *
 * Payment status transitions during execution:
 *   SCHEDULED → ACCEPTED (after validation in post-schedule flow)
 *   ACCEPTED  → PROCESSING (after notifications sent to all parties)
 */
@ActivityInterface
interface PaymentExecActivities {

    /**
     * Executes the actual payment against external payment rails.
     * Validates payment in the post-schedule flow and transitions
     * payment status from SCHEDULED → ACCEPTED.
     * Debit source, credit destination, settlement processing.
     */
    @ActivityMethod
    fun executePayment(context: PaymentExecContext)

    /**
     * Post-processing after successful execution.
     * Ledger updates, reconciliation records, status updates in core systems.
     */
    @ActivityMethod
    fun postProcess(context: PaymentExecContext)

    /**
     * Sends notifications after execution (email, SMS, webhooks).
     * After all parties are notified, transitions payment status
     * from ACCEPTED → PROCESSING.
     */
    @ActivityMethod
    fun sendNotifications(context: PaymentExecContext)
}
