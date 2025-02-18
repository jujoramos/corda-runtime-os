package net.corda.virtualnode.rpcops.common

import net.corda.libs.configuration.SmartConfig
import java.time.Duration

interface VirtualNodeSenderFactory {
    fun createSender(timeout: Duration, messagingConfig: SmartConfig): VirtualNodeSender
}
