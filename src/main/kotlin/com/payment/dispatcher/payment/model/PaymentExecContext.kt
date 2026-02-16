package com.payment.dispatcher.payment.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Accumulated execution context from Phase A (PaymentInitWorkflow).
 * Serialized as JSON CLOB in PAYMENT_EXEC_CONTEXT table.
 * Consumed by Phase B (PaymentExecWorkflow) to execute the payment.
 *
 * Contains all results from validation, enrichment, rules, and fee calculation
 * so that Phase B doesn't need to repeat any of this work.
 *
 * Versioned via [version] field for forward/backward compatibility.
 */
data class PaymentExecContext(
    val paymentId: String,
    val version: Int = CURRENT_VERSION,

    // Original request data
    val amount: BigDecimal,
    val currency: String,
    val sourceAccount: String,
    val destinationAccount: String,
    val paymentType: String,
    val scheduledExecTime: String, // ISO-8601 for serialization safety

    // Phase A results
    val validationResult: ValidationResult? = null,
    val enrichmentData: EnrichmentData? = null,
    val appliedRules: List<AppliedRule> = emptyList(),
    val feeCalculation: FeeCalculation? = null,
    val fxRateSnapshot: FxRateSnapshot? = null,

    // Tracing
    val initWorkflowId: String? = null,
    val contextCreatedAt: String = Instant.now().toString()
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val CONTEXT_TYPE = "PAYMENT_EXEC_V1"
    }
}

// ═══════════════════════════════════════════════════════════════════
// Phase A result types
// ═══════════════════════════════════════════════════════════════════

data class ValidationResult(
    val valid: Boolean,
    val checks: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class EnrichmentData(
    val sourceAccountName: String? = null,
    val destinationAccountName: String? = null,
    val routingDetails: RoutingDetails? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class RoutingDetails(
    val bankCode: String? = null,
    val branchCode: String? = null,
    val intermediaryBank: String? = null
)

data class AppliedRule(
    val ruleId: String,
    val ruleName: String,
    val outcome: String,
    val details: String? = null
)

data class FeeCalculation(
    val totalFee: BigDecimal,
    val components: List<FeeComponent> = emptyList()
)

data class FeeComponent(
    val type: String,
    val amount: BigDecimal,
    val description: String? = null
)

data class FxRateSnapshot(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: BigDecimal,
    val rateTimestamp: String, // ISO-8601
    val provider: String? = null
)
