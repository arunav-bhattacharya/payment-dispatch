package com.payment.dispatcher.framework.model

/**
 * Status lifecycle for items in the dispatch queue.
 *
 * READY ──(dispatcher claims)──> CLAIMED ──(exec wf started)──> DISPATCHED ──(success)──> COMPLETED
 *   ^                               |                                |
 *   |                               | (dispatch failed)              | (exec failed, retries left)
 *   |                               v                                v
 *   +<────────────────────── READY (retry_count++)                FAILED
 *   ^                                                               |
 *   | (stale recovery,                                              | (retry_count >= max)
 *   |  exec wf NOT_FOUND)                                          v
 *   +<──────────────────── CLAIMED (stale)                     DEAD_LETTER
 */
enum class QueueStatus {
    /** Queued and waiting for dispatcher to claim */
    READY,

    /** Claimed by dispatcher, pending workflow start */
    CLAIMED,

    /** Exec workflow started and confirmed running */
    DISPATCHED,

    /** Execution completed successfully */
    COMPLETED,

    /** Execution failed (may be retried if retry_count < max) */
    FAILED,

    /** Retries exhausted — requires manual investigation */
    DEAD_LETTER
}
