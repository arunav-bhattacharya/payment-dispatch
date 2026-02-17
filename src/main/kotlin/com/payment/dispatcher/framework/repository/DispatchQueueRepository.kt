package com.payment.dispatcher.framework.repository

import com.payment.dispatcher.framework.model.ClaimedItem
import com.payment.dispatcher.framework.model.QueueStatus
import com.payment.dispatcher.framework.repository.tables.ExecContextTable
import com.payment.dispatcher.framework.repository.tables.ExecQueueTable
import com.payment.dispatcher.framework.repository.tables.ExecRateConfigTable
import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Timestamp
import java.time.Instant

/**
 * Core dispatch queue operations using Kotlin Exposed DSL.
 * The claimBatch method uses raw JDBC for Oracle-native FOR UPDATE SKIP LOCKED,
 * since Exposed DSL only provides PostgreSQL-specific ForUpdateOption.
 * All other operations use Exposed DSL.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class DispatchQueueRepository {

    @Inject
    lateinit var dataSource: AgroalDataSource

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
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Claims a batch of dispatchable items atomically using FOR UPDATE SKIP LOCKED.
     *
     * The SELECT uses an OR predicate to pick up both:
     * - READY items whose scheduled_exec_time has arrived (normal dispatch)
     * - Stale CLAIMED items whose claimed_at is older than the threshold (self-healing)
     *
     * This unified query eliminates the need for a separate stale recovery step.
     * Stale CLAIMED items are simply re-claimed and re-dispatched. The deterministic
     * workflow ID + WorkflowExecutionAlreadyStarted handler in dispatchSingleItem()
     * ensures correctness: if the exec workflow is already running, it's treated as
     * success and marked DISPATCHED.
     *
     * Three-step process within a single JDBC transaction:
     * 1. SELECT FOR UPDATE SKIP LOCKED — lock candidate rows (raw SQL for Oracle)
     * 2. UPDATE locked rows — set status to CLAIMED (raw SQL, same connection)
     * 3. Fetch full details via Exposed DSL — return claimed items with config info
     *    and pre-loaded context JSON (joined with PAYMENT_EXEC_CONTEXT)
     *
     * Steps 1-2 use raw JDBC because Exposed DSL only has PostgreSQL ForUpdateOption.
     * Step 3 uses Exposed DSL since no locking is needed.
     *
     * @return List of claimed items ready for dispatch (may be empty)
     */
    fun claimBatch(
        itemType: String,
        batchSize: Int,
        cutoffTime: Instant,
        maxRetries: Int,
        staleClaimThresholdMins: Int,
        batchId: String
    ): List<ClaimedItem> {
        // Steps 1-2: Raw JDBC for Oracle FOR UPDATE SKIP LOCKED
        val claimedIds = claimBatchRaw(itemType, batchSize, cutoffTime, maxRetries, staleClaimThresholdMins, batchId)

        if (claimedIds.isEmpty()) {
            return emptyList()
        }

        // Step 3: Fetch full details + pre-load context JSON via Exposed DSL
        return transaction {
            val configRow = ExecRateConfigTable.selectAll()
                .where { ExecRateConfigTable.itemType eq itemType }
                .firstOrNull()

            val execWorkflowType = configRow?.get(ExecRateConfigTable.execWorkflowType)
                ?: error("No EXEC_RATE_CONFIG found for itemType=$itemType")
            val execTaskQueue = configRow[ExecRateConfigTable.execTaskQueue]

            (ExecQueueTable leftJoin ExecContextTable)
                .selectAll()
                .where { ExecQueueTable.dispatchBatchId eq batchId }
                .orderBy(ExecQueueTable.scheduledExecTime to SortOrder.ASC)
                .map { row ->
                    ClaimedItem(
                        itemId = row[ExecQueueTable.paymentId],
                        itemType = row[ExecQueueTable.itemType],
                        scheduledExecTime = row[ExecQueueTable.scheduledExecTime].toString(),
                        initWorkflowId = row[ExecQueueTable.initWorkflowId],
                        execWorkflowType = execWorkflowType,
                        execTaskQueue = execTaskQueue,
                        contextJson = row[ExecContextTable.contextJson]
                    )
                }
        }
    }

    /**
     * Raw JDBC implementation of SELECT FOR UPDATE SKIP LOCKED + UPDATE.
     * Oracle supports FOR UPDATE SKIP LOCKED natively.
     * Uses a single JDBC connection/transaction to ensure the locks are held
     * while the UPDATE executes.
     *
     * The SELECT uses an OR predicate to pick up both:
     * - READY items whose scheduled_exec_time has arrived and retry_count < maxRetries
     * - Stale CLAIMED items whose claimed_at is older than staleClaimThresholdMins
     *
     * @return List of payment IDs that were successfully claimed
     */
    private fun claimBatchRaw(
        itemType: String,
        batchSize: Int,
        cutoffTime: Instant,
        maxRetries: Int,
        staleClaimThresholdMins: Int,
        batchId: String
    ): List<String> {
        val candidateIds = mutableListOf<String>()
        val staleThreshold = Instant.now().minusSeconds(staleClaimThresholdMins.toLong() * 60)

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Step 1: SELECT FOR UPDATE SKIP LOCKED (Oracle-native syntax)
                // Unified query: picks up READY items + stale CLAIMED items in one pass
                val selectSql = """
                    SELECT payment_id FROM PAYMENT_EXEC_QUEUE
                    WHERE item_type = ?
                      AND (
                        (queue_status = ? AND scheduled_exec_time <= ? AND retry_count < ?)
                        OR
                        (queue_status = ? AND claimed_at <= ?)
                      )
                    ORDER BY scheduled_exec_time ASC
                    FETCH FIRST ? ROWS ONLY
                    FOR UPDATE SKIP LOCKED
                """.trimIndent()

                conn.prepareStatement(selectSql).use { ps ->
                    ps.setString(1, itemType)
                    // READY predicate
                    ps.setString(2, QueueStatus.READY.name)
                    ps.setTimestamp(3, Timestamp.from(cutoffTime))
                    ps.setInt(4, maxRetries)
                    // Stale CLAIMED predicate
                    ps.setString(5, QueueStatus.CLAIMED.name)
                    ps.setTimestamp(6, Timestamp.from(staleThreshold))
                    // FETCH FIRST
                    ps.setInt(7, batchSize)

                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            candidateIds.add(rs.getString("payment_id"))
                        }
                    }
                }

                if (candidateIds.isEmpty()) {
                    conn.commit()
                    return emptyList()
                }

                // Step 2: UPDATE locked rows to CLAIMED
                val placeholders = candidateIds.joinToString(",") { "?" }
                val updateSql = """
                    UPDATE PAYMENT_EXEC_QUEUE
                    SET queue_status = ?,
                        claimed_at = ?,
                        dispatch_batch_id = ?,
                        updated_at = ?
                    WHERE payment_id IN ($placeholders)
                """.trimIndent()

                val now = Timestamp.from(Instant.now())
                conn.prepareStatement(updateSql).use { ps ->
                    ps.setString(1, QueueStatus.CLAIMED.name)
                    ps.setTimestamp(2, now)
                    ps.setString(3, batchId)
                    ps.setTimestamp(4, now)
                    candidateIds.forEachIndexed { index, id ->
                        ps.setString(5 + index, id)
                    }
                    ps.executeUpdate()
                }

                conn.commit()
                logger.debug { "Claimed ${candidateIds.size} items for batchId=$batchId (itemType=$itemType)" }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

        return candidateIds
    }

    // ═══════════════════════════════════════════════════════════════════
    // Status Transitions — Called by dispatcher activities
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Transitions CLAIMED → DISPATCHED after exec workflow is started.
     * DISPATCHED is the terminal queue state — Temporal manages the
     * execution lifecycle from this point.
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
                logger.warn { "markDispatched: no CLAIMED row found for itemId=$itemId (may already be DISPATCHED)" }
            }
        }
    }

    /**
     * Resets a CLAIMED item back to READY for retry in next dispatch cycle.
     * Called when WorkflowClient.start() fails for an item during dispatch.
     */
    fun resetToReady(itemId: String, error: String?) {
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
                it[lastError] = error?.take(4000)
                it[updatedAt] = Instant.now()
            }
        }
    }

}
