package com.payment.dispatcher.framework.repository

import com.payment.dispatcher.framework.repository.tables.DispatchAuditLogTable
import jakarta.enterprise.context.ApplicationScoped
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * Insert-only audit log repository for dispatch operations.
 * Provides observability into dispatch cycles, individual dispatches, and failures.
 */
@ApplicationScoped
class DispatchAuditRepository {

    /**
     * Writes a single audit log entry.
     */
    fun log(
        batchId: String,
        itemType: String,
        paymentId: String? = null,
        action: String,
        statusBefore: String? = null,
        statusAfter: String? = null,
        detail: String? = null
    ) {
        transaction {
            DispatchAuditLogTable.insert {
                it[DispatchAuditLogTable.batchId] = batchId
                it[DispatchAuditLogTable.itemType] = itemType
                it[DispatchAuditLogTable.paymentId] = paymentId
                it[DispatchAuditLogTable.action] = action
                it[DispatchAuditLogTable.statusBefore] = statusBefore
                it[DispatchAuditLogTable.statusAfter] = statusAfter
                it[DispatchAuditLogTable.detail] = detail?.take(4000)
                it[DispatchAuditLogTable.createdAt] = Instant.now()
            }
        }
    }

    /**
     * Writes a batch summary audit entry.
     */
    fun logBatchSummary(
        batchId: String,
        itemType: String,
        totalDispatched: Int,
        totalFailed: Int,
        durationMs: Long
    ) {
        log(
            batchId = batchId,
            itemType = itemType,
            action = "BATCH_COMPLETE",
            detail = "total=${totalDispatched + totalFailed}, success=$totalDispatched, " +
                    "failed=$totalFailed, duration=${durationMs}ms"
        )
    }
}
