package net.corda.p2p.linkmanager.delivery

import java.time.Duration

internal class ConstantReplayCalculator(limitTotalReplays: Boolean, private val config: ReplayScheduler.ReplaySchedulerConfig
) : ReplayCalculator(limitTotalReplays, config) {

    override fun calculateReplayInterval(lastDelay: Duration): Duration {
        return config.baseReplayPeriod
    }
}