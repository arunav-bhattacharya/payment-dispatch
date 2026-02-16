package com.payment.dispatcher.framework.repository.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed DSL definition for DISPATCH_AUDIT_LOG table.
 * Insert-only audit trail for all dispatch operations.
 */
object DispatchAuditLogTable : Table("DISPATCH_AUDIT_LOG") {
    val auditId = long("audit_id").autoIncrement()
    val batchId = varchar("batch_id", 64)
    val itemType = varchar("item_type", 64).default("PAYMENT")
    val paymentId = varchar("payment_id", 128).nullable()
    val action = varchar("action", 64)
    val statusBefore = varchar("status_before", 32).nullable()
    val statusAfter = varchar("status_after", 32).nullable()
    val detail = varchar("detail", 4000).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(auditId, name = "pk_dispatch_audit_log")
}
