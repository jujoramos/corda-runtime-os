package net.corda.crypto.service.impl.bus.configuration

import net.corda.configuration.read.ConfigChangedEvent
import net.corda.configuration.read.ConfigurationReadService
import net.corda.crypto.component.impl.AbstractConfigurableComponent
import net.corda.crypto.component.impl.DependenciesTracker
import net.corda.crypto.service.HSMConfigurationBusService
import net.corda.crypto.service.HSMService
import net.corda.data.crypto.wire.hsm.configuration.HSMConfigurationRequest
import net.corda.data.crypto.wire.hsm.configuration.HSMConfigurationResponse
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.libs.configuration.helper.getConfig
import net.corda.messaging.api.subscription.RPCSubscription
import net.corda.messaging.api.subscription.config.RPCConfig
import net.corda.messaging.api.subscription.factory.SubscriptionFactory
import net.corda.schema.Schemas
import net.corda.schema.configuration.ConfigKeys.BOOT_CONFIG
import net.corda.schema.configuration.ConfigKeys.CRYPTO_CONFIG
import net.corda.schema.configuration.ConfigKeys.MESSAGING_CONFIG
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

@Component(service = [HSMConfigurationBusService::class])
class HSMConfigurationBusServiceImpl @Activate constructor(
    @Reference(service = LifecycleCoordinatorFactory::class)
    coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = ConfigurationReadService::class)
    configurationReadService: ConfigurationReadService,
    @Reference(service = SubscriptionFactory::class)
    private val subscriptionFactory: SubscriptionFactory,
    @Reference(service = HSMService::class)
    private val hsmService: HSMService
) : AbstractConfigurableComponent<HSMConfigurationBusServiceImpl.Impl>(
    coordinatorFactory = coordinatorFactory,
    myName = LifecycleCoordinatorName.forComponent<HSMConfigurationBusService>(),
    configurationReadService = configurationReadService,
    upstream = DependenciesTracker.Default(
        setOf(
            LifecycleCoordinatorName.forComponent<HSMService>(),
            LifecycleCoordinatorName.forComponent<ConfigurationReadService>()
        )
    ),
    configKeys = setOf(
        MESSAGING_CONFIG,
        BOOT_CONFIG,
        CRYPTO_CONFIG
    )
), HSMConfigurationBusService {
    private companion object {
        const val GROUP_NAME = "crypto.hsm.rpc.registration"
        const val CLIENT_NAME = "crypto.hsm.rpc.registration"
    }

    override fun createActiveImpl(event: ConfigChangedEvent): Impl {
        logger.info("Creating RPC subscription for '{}' topic", Schemas.Crypto.RPC_HSM_CONFIGURATION_MESSAGE_TOPIC)
        val messagingConfig = event.config.getConfig(MESSAGING_CONFIG)
        val processor = HSMConfigurationBusProcessor(hsmService, event)
        return Impl(
            subscriptionFactory.createRPCSubscription(
                rpcConfig = RPCConfig(
                    groupName = GROUP_NAME,
                    clientName = CLIENT_NAME,
                    requestTopic = Schemas.Crypto.RPC_HSM_CONFIGURATION_MESSAGE_TOPIC,
                    requestType = HSMConfigurationRequest::class.java,
                    responseType = HSMConfigurationResponse::class.java
                ),
                responderProcessor = processor,
                messagingConfig = messagingConfig
            ).also { it.start() }
        )
    }

    class Impl(
        val subscription: RPCSubscription<HSMConfigurationRequest, HSMConfigurationResponse>
    ) : AbstractImpl {
        override val downstream: DependenciesTracker =
            DependenciesTracker.Default(setOf(subscription.subscriptionName))

        override fun close() {
            subscription.close()
        }
    }
}