package com.payment.dispatcher.framework.context

/**
 * Generic context persistence service.
 * Type parameter T represents the domain-specific context data class
 * (e.g., PaymentExecContext, InvoiceExecContext).
 *
 * Implementations handle serialization and storage — the framework
 * doesn't prescribe how context is stored, only the interface.
 */
interface ExecutionContextService<T> {

    /**
     * Saves execution context for the given item.
     * Must be idempotent (safe on activity retry) — uses MERGE/upsert semantics.
     */
    fun save(itemId: String, itemType: String, context: T)

    /**
     * Loads execution context for the given item.
     * @throws IllegalStateException if context not found
     */
    fun load(itemId: String, clazz: Class<T>): T

    /**
     * Deletes execution context after successful completion.
     */
    fun delete(itemId: String)

    /**
     * Checks if context exists for the given item.
     */
    fun exists(itemId: String): Boolean
}
