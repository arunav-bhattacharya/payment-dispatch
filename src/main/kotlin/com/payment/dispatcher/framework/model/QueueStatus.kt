package com.payment.dispatcher.framework.model

/**
 * Status lifecycle for items in the dispatch queue.
 *
 * READY ──(dispatcher claims)──> CLAIMED ──(exec wf started)──> DISPATCHED (terminal)
 *   ^                               |
 *   |                               | (dispatch failed)
 *   |                               v
 *   +<────────────────────── READY (retry_count++)
 *   ^
 *   | (stale recovery,
 *   |  exec wf NOT_FOUND)
 *   +<──────────────────── CLAIMED (stale)
 *
 * DISPATCHED is the terminal queue state. Once the exec workflow is started
 * in Temporal, the queue has done its job. Temporal manages the execution
 * lifecycle (retries, timeouts, completion/failure) from that point.
 */
enum class QueueStatus {
    /** Queued and waiting for dispatcher to claim */
    READY,

    /** Claimed by dispatcher, pending workflow start */
    CLAIMED,

    /** Exec workflow started — terminal queue state. Temporal owns the lifecycle from here. */
    DISPATCHED
}
