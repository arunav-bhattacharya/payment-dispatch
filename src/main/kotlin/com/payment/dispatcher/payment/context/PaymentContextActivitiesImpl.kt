package com.payment.dispatcher.payment.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.dispatcher.framework.context.ExposedContextService
import com.payment.dispatcher.framework.metrics.DispatchMetrics
import com.payment.dispatcher.framework.repository.DispatchConfigRepository
import com.payment.dispatcher.framework.repository.DispatchQueueRepository
import com.payment.dispatcher.payment.model.PaymentExecContext
import io.quarkiverse.temporal.TemporalActivity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Payment-specific context activities implementation.
 * Composes generic framework services with payment-specific types.
 *
 * This is the key composition seam: another domain (e.g., Invoice)
 * would create InvoiceContextActivitiesImpl composing the same
 * generic services with InvoiceExecContext.
 */
@ApplicationScoped
@TemporalActivity(workers = ["payment-exec-worker", "payment-init-worker"])
class PaymentContextActivitiesImpl : PaymentContextActivities {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var queueRepo: DispatchQueueRepository

    @Inject
    lateinit var configRepo: DispatchConfigRepository

    @Inject
    lateinit var metrics: DispatchMetrics

    /** Lazy-initialized context service parameterized with PaymentExecContext */
    private val contextService by lazy {
        ExposedContextService<PaymentExecContext>(objectMapper)
    }

    companion object {
        private val log = Logger.getLogger(PaymentContextActivitiesImpl::class.java)
        private const val ITEM_TYPE = "PAYMENT"
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase A: Save context + Enqueue
    // ═══════════════════════════════════════════════════════════════════

    override fun saveContextAndEnqueue(
        context: PaymentExecContext,
        scheduledExecTime: String,
        initWorkflowId: String?
    ) {
        // Save context FIRST — if enqueue fails, orphaned context is harmless
        contextService.save(context.paymentId, ITEM_TYPE, context)

        // Then enqueue for dispatch
        queueRepo.enqueue(
            itemId = context.paymentId,
            itemType = ITEM_TYPE,
            scheduledExecTime = Instant.parse(scheduledExecTime),
            initWorkflowId = initWorkflowId
        )

        log.infof("Payment %s: context saved and enqueued for execution at %s",
            context.paymentId, scheduledExecTime)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase B: Complete + Cleanup
    // Note: loadContext was removed — context is now pre-loaded at dispatch
    // time (joined in DispatchQueueRepository.claimBatch) and passed
    // directly to the exec workflow as a JSON string parameter.
    // ═══════════════════════════════════════════════════════════════════

    override fun completeAndCleanup(paymentId: String) {
        // Mark DISPATCHED → COMPLETED in queue
        queueRepo.markCompleted(paymentId)

        // Delete the CLOB context (no longer needed)
        contextService.delete(paymentId)

        log.infof("Payment %s: completed and context cleaned up", paymentId)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase B: Mark Failed
    // ═══════════════════════════════════════════════════════════════════

    override fun markFailed(paymentId: String, error: String?) {
        val config = configRepo.findByItemType(ITEM_TYPE)
        val maxRetries = config?.maxDispatchRetries ?: 5

        val isDeadLetter = queueRepo.markFailed(paymentId, error, maxRetries)

        if (isDeadLetter) {
            metrics.recordDeadLetter()
            log.errorf("Payment %s moved to DEAD_LETTER: %s", paymentId, error?.take(200))
        } else {
            log.warnf("Payment %s marked as FAILED (will retry): %s", paymentId, error?.take(200))
        }
    }
}
