package com.payment.dispatcher.framework.schedule

import io.temporal.client.WorkflowClient
import io.temporal.client.schedules.Schedule
import io.temporal.client.schedules.ScheduleActionStartWorkflow
import io.temporal.client.schedules.ScheduleClient
import io.temporal.client.schedules.ScheduleIntervalSpec
import io.temporal.client.schedules.ScheduleOptions
import io.temporal.client.schedules.SchedulePolicy
import io.temporal.client.schedules.ScheduleSpec
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import io.temporal.serviceclient.WorkflowServiceStubs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Creates and manages Temporal Schedules for dispatching.
 * Each item type gets its own schedule (e.g., "dispatch-payment-schedule").
 *
 * The schedule fires DispatcherWorkflow at a configurable interval
 * with OverlapPolicy.SKIP to prevent concurrent dispatchers.
 */
@ApplicationScoped
class DispatchScheduleSetup {

    @Inject
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var workflowServiceStubs: WorkflowServiceStubs

    companion object {
        private val log = Logger.getLogger(DispatchScheduleSetup::class.java)
    }

    /**
     * Creates a Temporal Schedule for the dispatcher.
     * Idempotent: logs a warning if the schedule already exists.
     *
     * @param scheduleId   Unique schedule identifier (e.g., "dispatch-payment-schedule")
     * @param taskQueue    Task queue for the dispatcher worker
     * @param intervalSecs Dispatch interval in seconds (default: 5)
     * @param itemType     Item type passed as workflow argument (e.g., "PAYMENT")
     */
    fun createOrUpdate(
        scheduleId: String,
        taskQueue: String,
        intervalSecs: Int,
        itemType: String
    ) {
        try {
            val scheduleClient = ScheduleClient.newInstance(workflowServiceStubs)

            val schedule = Schedule.newBuilder()
                .setAction(
                    ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType("DispatcherWorkflow")
                        .setArguments(itemType)
                        .setOptions(
                            io.temporal.client.WorkflowOptions.newBuilder()
                                .setTaskQueue(taskQueue)
                                .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                                .build()
                        )
                        .build()
                )
                .setSpec(
                    ScheduleSpec.newBuilder()
                        .setIntervals(
                            listOf(ScheduleIntervalSpec(Duration.ofSeconds(intervalSecs.toLong())))
                        )
                        .build()
                )
                .setPolicy(
                    SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build()
                )
                .build()

            scheduleClient.createSchedule(
                scheduleId,
                schedule,
                ScheduleOptions.newBuilder().build()
            )

            log.infof("Created dispatch schedule '%s' for itemType=%s (interval=%ds, taskQueue=%s)",
                scheduleId, itemType, intervalSecs, taskQueue)

        } catch (e: io.grpc.StatusRuntimeException) {
            // Schedule already exists — ALREADY_EXISTS status code
            if (e.status.code == io.grpc.Status.Code.ALREADY_EXISTS) {
                log.infof("Dispatch schedule '%s' already exists — skipping creation", scheduleId)
            } else {
                log.warnf("Failed to create dispatch schedule '%s': %s", scheduleId, e.message)
            }
        } catch (e: Exception) {
            log.warnf("Failed to create dispatch schedule '%s': %s", scheduleId, e.message)
            // Non-fatal: schedule may already exist from a previous deployment
        }
    }
}
