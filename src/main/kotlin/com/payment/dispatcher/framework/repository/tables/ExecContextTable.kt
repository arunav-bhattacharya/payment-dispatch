package com.payment.dispatcher.framework.repository.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed DSL definition for PAYMENT_EXEC_CONTEXT table.
 * Stores accumulated execution context as JSON CLOB.
 * Written by Phase A (init workflow), consumed by Phase B (exec workflow).
 */
object ExecContextTable : Table("PAYMENT_EXEC_CONTEXT") {
    val paymentId = varchar("payment_id", 128)
    val itemType = varchar("item_type", 64).default("PAYMENT")
    val contextJson = text("context_json")
    val contextVersion = integer("context_version").default(1)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(paymentId, name = "pk_payment_exec_context")
}
