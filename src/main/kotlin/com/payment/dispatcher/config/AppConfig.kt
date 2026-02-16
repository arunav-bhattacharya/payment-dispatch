package com.payment.dispatcher.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

/**
 * Application-level dispatch configuration via Quarkus SmallRye Config.
 * These are startup-time settings — runtime tuning uses EXEC_RATE_CONFIG table.
 */
@ConfigMapping(prefix = "dispatch")
interface AppConfig {

    /** Default item type for the payment dispatch schedule */
    @WithDefault("PAYMENT")
    fun defaultItemType(): String

    /** Temporal Schedule ID for the dispatcher */
    @WithDefault("dispatch-payment-schedule")
    fun scheduleId(): String

    /** Whether to auto-create the Temporal Schedule on startup */
    @WithDefault("true")
    fun autoCreateSchedule(): Boolean

    /** Dispatch interval in seconds (used when creating the schedule) */
    @WithDefault("5")
    fun dispatchIntervalSecs(): Int

    /** Task queue configuration */
    fun taskQueues(): TaskQueuesConfig

    interface TaskQueuesConfig {
        @WithDefault("dispatch-task-queue")
        fun dispatcher(): String

        @WithDefault("payment-init-task-queue")
        fun paymentInit(): String

        @WithDefault("payment-exec-task-queue")
        fun paymentExec(): String
    }
}
