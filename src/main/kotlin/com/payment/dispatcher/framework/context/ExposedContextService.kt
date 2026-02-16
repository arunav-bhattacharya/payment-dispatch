package com.payment.dispatcher.framework.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.dispatcher.framework.repository.tables.ExecContextTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Generic Exposed-based context persistence using Oracle CLOB (via Exposed text column).
 * Uses Jackson for JSON serialization/deserialization.
 *
 * Not @ApplicationScoped directly — domain-specific subclasses provide the concrete type.
 * Example: PaymentContextService extends ExposedContextService<PaymentExecContext>.
 *
 * Uses Exposed's upsert() for idempotent saves (handles activity retries gracefully).
 */
open class ExposedContextService<T>(
    private val objectMapper: ObjectMapper
) : ExecutionContextService<T> {

    companion object {
        private val log = Logger.getLogger(ExposedContextService::class.java)
    }

    /**
     * Saves context as JSON CLOB using MERGE (upsert) for idempotency.
     * Safe on Temporal activity retries — won't fail on duplicate insert.
     */
    override fun save(itemId: String, itemType: String, context: T) {
        val json = objectMapper.writeValueAsString(context)
        val now = Instant.now()

        transaction {
            ExecContextTable.upsert(ExecContextTable.paymentId) {
                it[paymentId] = itemId
                it[ExecContextTable.itemType] = itemType
                it[contextJson] = json
                it[contextVersion] = 1
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        log.debugf("Saved context for itemId=%s (type=%s, size=%d bytes)",
            itemId, itemType, json.length)
    }

    /**
     * Loads and deserializes context from Oracle CLOB.
     * @throws IllegalStateException if context not found
     */
    override fun load(itemId: String, clazz: Class<T>): T {
        return transaction {
            val row = ExecContextTable.selectAll()
                .where { ExecContextTable.paymentId eq itemId }
                .firstOrNull()
                ?: throw IllegalStateException("Execution context not found for itemId=$itemId")

            val json = row[ExecContextTable.contextJson]
            objectMapper.readValue(json, clazz)
        }
    }

    /**
     * Deletes context after successful execution (cleanup).
     */
    override fun delete(itemId: String) {
        transaction {
            ExecContextTable.deleteWhere { paymentId eq itemId }
        }
        log.debugf("Deleted context for itemId=%s", itemId)
    }

    /**
     * Checks if context exists for diagnostics and guards.
     */
    override fun exists(itemId: String): Boolean {
        return transaction {
            ExecContextTable.selectAll()
                .where { ExecContextTable.paymentId eq itemId }
                .count() > 0
        }
    }
}
