package com.payment.dispatcher.payment.model

/**
 * Payment lifecycle states tracked in the payments database.
 * These represent the business-level payment status, separate from
 * the dispatch queue status (QueueStatus).
 *
 * Flow: SCHEDULED → ACCEPTED → PROCESSING
 *
 * - SCHEDULED: Payment has been validated, enriched, rules applied,
 *   persisted to the payments DB, and enqueued for dispatch.
 *   The payment is waiting for its scheduled execution time.
 *
 * - ACCEPTED: Payment has been validated in the post-schedule flow
 *   (Phase B execution). The payment is confirmed ready for processing.
 *
 * - PROCESSING: Payment execution is complete and all parties
 *   (payer, payee, integration partners) have been notified.
 */
enum class PaymentStatus {
    SCHEDULED,
    ACCEPTED,
    PROCESSING
}
