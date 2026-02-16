package com.payment.dispatcher.framework.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Centralized dispatch metrics exported via Micrometer / Prometheus.
 * Provides counters for dispatch operations and gauges for queue health.
 *
 * Prometheus endpoint: /q/metrics
 *
 * Recommended alert rules:
 * - dispatch.queue.depth{status="READY"} > 10000 for 5m
 * - rate(dispatch.dead.letter.total[5m]) > 0 for 1m
 * - rate(dispatch.stale.recovered.total[5m]) > 1 for 5m
 * - rate(dispatch.workflow.start.failures.total[5m]) > 5 for 2m
 */
@ApplicationScoped
class DispatchMetrics {

    @Inject
    lateinit var registry: MeterRegistry

    private lateinit var batchClaimedCounter: Counter
    private lateinit var dispatchCounter: Counter
    private lateinit var dispatchFailureCounter: Counter
    private lateinit var staleRecoveryCounter: Counter
    private lateinit var deadLetterCounter: Counter
    private lateinit var dispatchCycleTimer: Timer

    @PostConstruct
    fun init() {
        batchClaimedCounter = Counter.builder("dispatch.batch.claimed")
            .description("Total items claimed across all dispatch batches")
            .register(registry)

        dispatchCounter = Counter.builder("dispatch.workflow.started")
            .description("Total exec workflows successfully started")
            .register(registry)

        dispatchFailureCounter = Counter.builder("dispatch.workflow.start.failures")
            .description("Total exec workflow start failures")
            .register(registry)

        staleRecoveryCounter = Counter.builder("dispatch.stale.recovered")
            .description("Total stale claims recovered back to READY")
            .register(registry)

        deadLetterCounter = Counter.builder("dispatch.dead.letter")
            .description("Total items moved to DEAD_LETTER status")
            .register(registry)

        dispatchCycleTimer = Timer.builder("dispatch.cycle.duration")
            .description("Duration of a complete dispatch cycle")
            .register(registry)
    }

    fun recordBatchClaimed(count: Int) {
        batchClaimedCounter.increment(count.toDouble())
    }

    fun recordDispatch() {
        dispatchCounter.increment()
    }

    fun recordDispatchFailure() {
        dispatchFailureCounter.increment()
    }

    fun recordStaleRecovery() {
        staleRecoveryCounter.increment()
    }

    fun recordDeadLetter() {
        deadLetterCounter.increment()
    }

    fun startCycleTimer(): Timer.Sample {
        return Timer.start(registry)
    }

    fun stopCycleTimer(sample: Timer.Sample) {
        sample.stop(dispatchCycleTimer)
    }
}
