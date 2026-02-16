package com.payment.dispatcher.payment.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Incoming payment request DTO.
 * Received via REST API or Kafka and used to start PaymentInitWorkflow.
 */
data class PaymentRequest(
    val paymentId: String,
    val amount: BigDecimal,
    val currency: String,
    val sourceAccount: String,
    val destinationAccount: String,
    val paymentType: String = "STANDARD",
    val scheduledExecTime: Instant
)
