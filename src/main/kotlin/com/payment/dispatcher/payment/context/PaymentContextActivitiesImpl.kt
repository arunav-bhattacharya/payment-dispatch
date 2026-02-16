package com.payment.dispatcher.payment.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.dispatcher.framework.context.ExposedContextService
import com.payment.dispatcher.framework.repository.DispatchQueueRepository
import com.payment.dispatcher.payment.model.PaymentExecContext
import io.quarkiverse.temporal.TemporalActivity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Payment-specific context activities — Phase A only.
 * Composes generic framework services with payment-specific types.
 *
 * Dispatch lifecycle concerns (complete, fail, cleanup) are handled by
 * DispatchableWorkflow + DispatcherActivities at the framework level.
 *
 * This is the key composition seam: another domain (e.g., Invoice)
 * would create InvoiceContextActivitiesImpl composing the same
 * generic services with InvoiceExecContext.
 */
@ApplicationScoped
@TemporalActivity(workers = ["payment-init-worker"])
class PaymentContextActivitiesImpl : PaymentContextActivities {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var queueRepo: DispatchQueueRepository

    /** Lazy-initialized context service parameterized with PaymentExecContext */
    private val contextService by lazy {
        ExposedContextService<PaymentExecContext>(objectMapper)
    }

    companion object {
        private val log = Logger.getLogger(PaymentContextActivitiesImpl::class.java)
        private const val ITEM_TYPE = "PAYMENT"
    }

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
}
