package com.payment.dispatcher.framework.repository

import com.payment.dispatcher.framework.model.ClaimedItem
import com.payment.dispatcher.framework.model.QueueStatus
import com.payment.dispatcher.framework.repository.tables.ExecQueueTable
import com.payment.dispatcher.framework.repository.tables.ExecRateConfigTable
import jakarta.enterprise.context.ApplicationScoped
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Core dispatch queue operations using Kotlin Exposed DSL.
 * All concurrency-critical operations (claimBatch, stale recovery) use
 * FOR UPDATE SKIP LOCKED for contention-free claiming.
 */
@ApplicationScoped
class DispatchQueueRepository {

    companion object {
        private val log = Logger.getLogger(DispatchQueueRepository::class.java)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Enqueue — Called by init workflows (Phase A)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Inserts a new item into the dispatch queue with status READY.
     * Called at the end of Phase A (init workflow) after context is saved.
     */
    fun enqueue(
        itemId: String,
        itemType: String,
        scheduledExecTime: Instant,
        initWorkflowId: String?
    ) {
        transaction {
            ExecQueueTable.insert {
                it[paymentId] = itemId
                it[ExecQueueTable.itemType] = itemType
                it[queueStatus] = QueueStatus.READY.name
                it[ExecQueueTable.scheduledExecTime] = scheduledExecTime
                it[ExecQueueTable.initWorkflowId] = initWorkflowId
                it[retryCount] = 0
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch Claim — Called by dispatcher activities
    // Fixed from review P0 #2: two-step SELECT + UPDATE (not nested)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Claims a batch of READY items atomically using FOR UPDATE SKIP LOCKED.
     *
     * Three-step process within a single transaction:
     * 1. SELECT FOR UPDATE SKIP LOCKED — lock candidate rows without contention
     * 2. UPDATE locked rows — set status to CLAIMED
     * 3. Fetch full details — return claimed items with config info
     *
     * @return List of claimed items ready for dispatch (may be empty)
     */
    fun claimBatch(
        itemType: String,
        batchSize: Int,
        cutoffTime: Instant,
        maxRetries: Int,
        batchId: String
    ): List<ClaimedItem> {
        return transaction {
            // Step 1: Lock candidate rows with SKIP LOCKED
            val candidateIds = ExecQueueTable
                .select(ExecQueueTable.paymentId)
                .where {
                    (ExecQueueTable.itemType eq itemType) and
                    (ExecQueueTable.queueStatus eq QueueStatus.READY.name) and
                    (ExecQueueTable.scheduledExecTime lessEq cutoffTime) and
                    (ExecQueueTable.retryCount less maxRetries)
                }
                .orderBy(ExecQueueTable.scheduledExecTime to SortOrder.ASC)
                .limit(batchSize)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(mode = ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { it[ExecQueueTable.paymentId] }

            if (candidateIds.isEmpty()) {
                return@transaction emptyList()
            }

            // Step 2: Update the locked rows to CLAIMED
            val now = Instant.now()
            ExecQueueTable.update({ ExecQueueTable.paymentId inList candidateIds }) {
                it[queueStatus] = QueueStatus.CLAIMED.name
                it[claimedAt] = now
                it[dispatchBatchId] = batchId
                it[updatedAt] = now
            }

            // Step 3: Fetch full details joined with config for workflow type/queue
            val configRow = ExecRateConfigTable.selectAll()
                .where { ExecRateConfigTable.itemType eq itemType }
                .firstOrNull()

            val execWorkflowType = configRow?.get(ExecRateConfigTable.execWorkflowType)
                ?: error("No EXEC_RATE_CONFIG found for itemType=$itemType")
            val execTaskQueue = configRow[ExecRateConfigTable.execTaskQueue]

            ExecQueueTable.selectAll()
                .where { ExecQueueTable.dispatchBatchId eq batchId }
                .orderBy(ExecQueueTable.scheduledExecTime to SortOrder.ASC)
                .map { row ->
                    ClaimedItem(
                        itemId = row[ExecQueueTable.paymentId],
                        itemType = row[ExecQueueTable.itemType],
                        scheduledExecTime = row[ExecQueueTable.scheduledExecTime].toString(),
                        initWorkflowId = row[ExecQueueTable.initWorkflowId],
                        execWorkflowType = execWorkflowType,
                        execTaskQueue = execTaskQueue
                    )
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Status Transitions — Called by dispatcher and exec workflows
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Transitions CLAIMED → DISPATCHED after exec workflow is started.
     * Called by the dispatcher's dispatchBatch activity.
     */
    fun markDispatched(itemId: String, execWorkflowId: String) {
        transaction {
            val updated = ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                (ExecQueueTable.queueStatus eq QueueStatus.CLAIMED.name)
            }) {
                it[queueStatus] = QueueStatus.DISPATCHED.name
                it[dispatchedAt] = Instant.now()
                it[ExecQueueTable.execWorkflowId] = execWorkflowId
                it[updatedAt] = Instant.now()
            }

            if (updated == 0) {
                log.warnf("markDispatched: no CLAIMED row found for itemId=%s (may already be DISPATCHED)", itemId)
            }
        }
    }

    /**
     * Transitions DISPATCHED → COMPLETED on successful execution.
     * Called by the exec workflow's completeAndCleanup activity.
     */
    fun markCompleted(itemId: String) {
        transaction {
            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                (ExecQueueTable.queueStatus eq QueueStatus.DISPATCHED.name)
            }) {
                it[queueStatus] = QueueStatus.COMPLETED.name
                it[completedAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    /**
     * Marks an item as FAILED or DEAD_LETTER based on retry count.
     * If retry_count + 1 >= maxRetries, transitions to DEAD_LETTER (terminal).
     * Otherwise, transitions to FAILED (retryable).
     *
     * Called by the exec workflow's markFailed activity.
     *
     * @return true if the item was moved to DEAD_LETTER
     */
    fun markFailed(itemId: String, error: String?, maxRetries: Int): Boolean {
        return transaction {
            // Read current retry count
            val currentRow = ExecQueueTable.selectAll()
                .where { ExecQueueTable.paymentId eq itemId }
                .firstOrNull() ?: return@transaction false

            val currentRetryCount = currentRow[ExecQueueTable.retryCount]
            val newRetryCount = currentRetryCount + 1
            val isDeadLetter = newRetryCount >= maxRetries

            val newStatus = if (isDeadLetter) QueueStatus.DEAD_LETTER.name else QueueStatus.FAILED.name

            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                (ExecQueueTable.queueStatus eq QueueStatus.DISPATCHED.name)
            }) {
                it[queueStatus] = newStatus
                it[retryCount] = newRetryCount
                it[lastError] = error?.take(4000)
                it[updatedAt] = Instant.now()
            }

            if (isDeadLetter) {
                log.warnf("Item %s moved to DEAD_LETTER after %d retries: %s",
                    itemId, newRetryCount, error?.take(200))
            }

            isDeadLetter
        }
    }

    /**
     * Resets a CLAIMED item back to READY for retry in next dispatch cycle.
     * Called when WorkflowClient.start() fails for a specific item.
     */
    fun resetToReady(itemId: String, error: String?) {
        transaction {
            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                (ExecQueueTable.queueStatus inList listOf(
                    QueueStatus.CLAIMED.name,
                    QueueStatus.FAILED.name
                ))
            }) {
                it[queueStatus] = QueueStatus.READY.name
                it[claimedAt] = null
                it[dispatchBatchId] = null
                it[execWorkflowId] = null
                it[retryCount] = ExecQueueTable.retryCount + 1
                it[lastError] = error?.take(4000)
                it[updatedAt] = Instant.now()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stale Claim Recovery
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Finds CLAIMED items older than the threshold that may be stuck.
     * The caller should check Temporal for each to confirm the exec workflow
     * is NOT running before resetting to READY.
     *
     * @return List of (itemId, execWorkflowId) pairs for stale claims
     */
    fun findStaleClaims(
        itemType: String,
        thresholdMins: Int,
        maxRecovery: Int
    ): List<StaleClaim> {
        val staleThreshold = Instant.now().minusSeconds(thresholdMins.toLong() * 60)

        return transaction {
            ExecQueueTable
                .select(ExecQueueTable.paymentId, ExecQueueTable.execWorkflowId)
                .where {
                    (ExecQueueTable.itemType eq itemType) and
                    (ExecQueueTable.queueStatus eq QueueStatus.CLAIMED.name) and
                    (ExecQueueTable.claimedAt lessEq staleThreshold)
                }
                .limit(maxRecovery)
                .map { row ->
                    StaleClaim(
                        itemId = row[ExecQueueTable.paymentId],
                        execWorkflowId = row[ExecQueueTable.execWorkflowId]
                    )
                }
        }
    }

    /**
     * Resets a stale CLAIMED item back to READY.
     * Should only be called after confirming the exec workflow does NOT exist in Temporal.
     */
    fun resetStaleToReady(itemId: String) {
        transaction {
            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                (ExecQueueTable.queueStatus eq QueueStatus.CLAIMED.name)
            }) {
                it[queueStatus] = QueueStatus.READY.name
                it[claimedAt] = null
                it[dispatchBatchId] = null
                it[execWorkflowId] = null
                it[retryCount] = ExecQueueTable.retryCount + 1
                it[lastError] = "Stale claim recovery"
                it[updatedAt] = Instant.now()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Query Helpers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Gets the current status of an item. Used for diagnostics and guards.
     */
    fun getStatus(itemId: String): QueueStatus? {
        return transaction {
            ExecQueueTable
                .select(ExecQueueTable.queueStatus)
                .where { ExecQueueTable.paymentId eq itemId }
                .firstOrNull()
                ?.let { QueueStatus.valueOf(it[ExecQueueTable.queueStatus]) }
        }
    }

    /**
     * Counts items by status for a given item type. Used for metrics/monitoring.
     */
    fun countByStatus(itemType: String, status: QueueStatus): Long {
        return transaction {
            ExecQueueTable.selectAll()
                .where {
                    (ExecQueueTable.itemType eq itemType) and
                    (ExecQueueTable.queueStatus eq status.name)
                }
                .count()
        }
    }
}

/**
 * Represents a stale claim found during recovery.
 */
data class StaleClaim(
    val itemId: String,
    val execWorkflowId: String?
)
