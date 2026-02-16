package com.payment.dispatcher.payment.init

import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Phase A activities: validate, enrich, apply rules, calculate fees.
 * These represent the existing business logic that runs before payment execution.
 *
 * Activity results are JSON strings for Temporal serialization simplicity.
 * The buildContext activity assembles them into a PaymentExecContext.
 */
@ActivityInterface
interface PaymentInitActivities {

    /** Validates payment against business rules and account state */
    @ActivityMethod
    fun validatePayment(paymentId: String, requestJson: String): String

    /** Enriches payment with account names, routing details, metadata */
    @ActivityMethod
    fun enrichPayment(paymentId: String, requestJson: String): String

    /** Applies business/compliance rules engine */
    @ActivityMethod
    fun applyRules(paymentId: String, requestJson: String): String

    /** Calculates fees based on payment type, amount, corridor */
    @ActivityMethod
    fun calculateFees(paymentId: String, requestJson: String): String

    /** Determines the scheduled execution time for this payment */
    @ActivityMethod
    fun determineExecTime(paymentId: String, requestJson: String): String

    /**
     * Assembles all Phase A results into a single PaymentExecContext.
     * This is the context that Phase B (exec workflow) will use.
     */
    @ActivityMethod
    fun buildContext(
        paymentId: String,
        requestJson: String,
        validationResultJson: String,
        enrichmentDataJson: String,
        appliedRulesJson: String,
        feeCalculationJson: String
    ): PaymentExecContext
}
