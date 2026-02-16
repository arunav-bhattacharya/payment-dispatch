package com.payment.dispatcher.payment.exec

import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Phase B execution activities: the actual payment processing.
 * These use the accumulated context from Phase A — no need to re-validate,
 * re-enrich, re-calculate fees, etc.
 */
@ActivityInterface
interface PaymentExecActivities {

    /**
     * Executes the actual payment against external payment rails.
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
     */
    @ActivityMethod
    fun sendNotifications(context: PaymentExecContext)
}
