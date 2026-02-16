package com.payment.dispatcher.config

import com.payment.dispatcher.framework.schedule.DispatchScheduleSetup
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Creates the Temporal dispatch schedule on application startup.
 * Non-fatal if schedule already exists from a previous deployment.
 */
@ApplicationScoped
class DispatchScheduleInitializer {

    @Inject
    lateinit var scheduleSetup: DispatchScheduleSetup

    @Inject
    lateinit var config: AppConfig

    companion object {
        private val log = Logger.getLogger(DispatchScheduleInitializer::class.java)
    }

    fun onStart(@Observes event: StartupEvent) {
        if (!config.autoCreateSchedule()) {
            log.info("Auto-create schedule disabled — skipping")
            return
        }

        try {
            scheduleSetup.createOrUpdate(
                scheduleId = config.scheduleId(),
                taskQueue = config.taskQueues().dispatcher(),
                intervalSecs = config.dispatchIntervalSecs(),
                itemType = config.defaultItemType()
            )
            log.infof("Dispatch schedule initialized: scheduleId=%s, interval=%ds, itemType=%s",
                config.scheduleId(), config.dispatchIntervalSecs(), config.defaultItemType())
        } catch (e: Exception) {
            log.warnf("Failed to initialize dispatch schedule: %s (non-fatal — may already exist)",
                e.message)
        }
    }
}
