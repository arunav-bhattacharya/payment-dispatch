package com.payment.dispatcher.framework.repository

import com.payment.dispatcher.framework.model.DispatchConfig
import com.payment.dispatcher.framework.repository.tables.ExecRateConfigTable
import jakarta.enterprise.context.ApplicationScoped
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Reads dispatch configuration from EXEC_RATE_CONFIG table.
 * Configuration can be updated at runtime via SQL — no redeploy needed.
 */
@ApplicationScoped
class DispatchConfigRepository {

    /**
     * Loads dispatch configuration for the given item type.
     * Returns null if no configuration exists.
     */
    fun findByItemType(itemType: String): DispatchConfig? {
        return transaction {
            ExecRateConfigTable.selectAll()
                .where { ExecRateConfigTable.itemType eq itemType }
                .firstOrNull()
                ?.let { row ->
                    DispatchConfig(
                        itemType = row[ExecRateConfigTable.itemType],
                        enabled = row[ExecRateConfigTable.enabled] == 1,
                        batchSize = row[ExecRateConfigTable.batchSize],
                        dispatchIntervalSecs = row[ExecRateConfigTable.dispatchIntervalSecs],
                        jitterWindowMs = row[ExecRateConfigTable.jitterWindowMs],
                        preStartBufferMins = row[ExecRateConfigTable.preStartBufferMins],
                        maxDispatchRetries = row[ExecRateConfigTable.maxDispatchRetries],
                        staleClaimThresholdMins = row[ExecRateConfigTable.staleClaimThresholdMins],
                        maxStaleRecoveryPerCycle = row[ExecRateConfigTable.maxStaleRecoveryPerCycle],
                        execWorkflowType = row[ExecRateConfigTable.execWorkflowType],
                        execTaskQueue = row[ExecRateConfigTable.execTaskQueue]
                    )
                }
        }
    }
}
