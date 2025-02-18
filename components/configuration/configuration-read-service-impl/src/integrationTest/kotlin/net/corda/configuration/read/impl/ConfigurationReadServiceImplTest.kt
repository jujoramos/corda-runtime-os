package net.corda.configuration.read.impl

import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.corda.configuration.read.ConfigurationReadService
import net.corda.data.config.Configuration
import net.corda.data.config.ConfigurationSchemaVersion
import net.corda.db.messagebus.testkit.DBSetup
import net.corda.libs.configuration.SmartConfig
import net.corda.libs.configuration.SmartConfigFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.registry.LifecycleRegistry
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas.Config.Companion.CONFIG_TOPIC
import net.corda.schema.configuration.BootConfig.BOOT_JDBC_URL
import net.corda.schema.configuration.BootConfig.INSTANCE_ID
import net.corda.schema.configuration.ConfigKeys.BOOT_CONFIG
import net.corda.schema.configuration.ConfigKeys.DB_CONFIG
import net.corda.schema.configuration.ConfigKeys.FLOW_CONFIG
import net.corda.schema.configuration.ConfigKeys.JDBC_URL
import net.corda.schema.configuration.MessagingConfig.Bus.BUS_TYPE
import net.corda.test.util.eventually
import net.corda.v5.base.util.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.osgi.test.common.annotation.InjectService
import org.osgi.test.junit5.service.ServiceExtension

@ExtendWith(ServiceExtension::class, DBSetup::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ConfigurationReadServiceImplTest {

    companion object {
        private const val JDBC_URL_DATA = "testDataToTriggerBootDBParamLogic"
        private const val BOOT_CONFIG_STRING = """
            $INSTANCE_ID = 1
            $BUS_TYPE = DATABASE
            $BOOT_JDBC_URL = $JDBC_URL_DATA
        """

        private const val DB_CONFIG_STRING = """
            $JDBC_URL = $JDBC_URL_DATA
        """

        private const val TIMEOUT = 10000L
    }

    @InjectService(timeout = 4000)
    lateinit var configurationReadService: ConfigurationReadService

    @InjectService(timeout = 4000)
    lateinit var lifecycleRegistry: LifecycleRegistry

    @InjectService(timeout = 4000)
    lateinit var publisherFactory: PublisherFactory

    private val smartConfigFactory = SmartConfigFactory.create(ConfigFactory.empty())

    @Test
    fun `config read service delivers configuration updates to clients`() {
        val bootConfig = smartConfigFactory.create(ConfigFactory.parseString(BOOT_CONFIG_STRING))
        configurationReadService.start()
        configurationReadService.bootstrapConfig(bootConfig)
        val publisher = publisherFactory.createPublisher(PublisherConfig("foo"), bootConfig)
        val receivedKeys = mutableSetOf<String>()
        var receivedConfig = mapOf<String, SmartConfig>()
        eventually(duration = 5.seconds) {
            assertEquals(
                LifecycleStatus.UP,
                lifecycleRegistry.componentStatus()[LifecycleCoordinatorName.forComponent<ConfigurationReadService>()]?.status
            )
        }
        val reg = configurationReadService.registerForUpdates { keys, config ->
            receivedKeys.addAll(keys)
            receivedConfig = config
        }
        eventually {
            assertTrue(receivedKeys.contains(BOOT_CONFIG))
            assertEquals(bootConfig, receivedConfig[BOOT_CONFIG], "Incorrect config")
        }

        // Publish new configuration and verify it gets delivered
        val flowConfig = smartConfigFactory.create(ConfigFactory.parseMap(mapOf("foo" to "bar")))
        val confString = flowConfig.root().render()
        publisher.publish(
            listOf(
                Record(
                    CONFIG_TOPIC,
                    FLOW_CONFIG,
                    Configuration(confString, confString, 0, ConfigurationSchemaVersion(1, 0))
                )
            )
        )
        eventually {
            assertTrue(receivedKeys.contains(FLOW_CONFIG), "$FLOW_CONFIG key was missing from received keys")
            assertEquals(flowConfig, receivedConfig[FLOW_CONFIG], "Incorrect config")
        }

        // Cleanup
        reg.close()
        publisher.close()
        configurationReadService.close()
    }

    @Test
    fun `when a client registers all current configuration is delivered to the client`() {
        val bootConfig = smartConfigFactory.create(ConfigFactory.parseString(BOOT_CONFIG_STRING))

        val latch = CountDownLatch(1)
        val prepareLatch = CountDownLatch(1)
        configurationReadService.start()
        configurationReadService.bootstrapConfig(bootConfig)

        // Publish flow config and wait until it has been received by the service
        val flowConfig = smartConfigFactory.create(ConfigFactory.parseMap(mapOf("foo" to "baz")))
        val confString = flowConfig.root().render()
        val schemaVersion = ConfigurationSchemaVersion(1, 0)
        val publisher = publisherFactory.createPublisher(PublisherConfig("foo"), bootConfig)
        publisher.publish(
            listOf(
                Record(
                    CONFIG_TOPIC,
                    FLOW_CONFIG,
                    Configuration(confString, confString, 0, schemaVersion)
                )
            )
        )
        eventually(duration = 5.seconds) {
            assertTrue(configurationReadService.isRunning)
        }
        val reg1 = configurationReadService.registerForUpdates { keys, _ ->
            if (keys.contains(FLOW_CONFIG)) {
                prepareLatch.countDown()
            }
        }
        prepareLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
        assertEquals(
            LifecycleStatus.UP,
            lifecycleRegistry.componentStatus()[LifecycleCoordinatorName.forComponent<ConfigurationReadService>()]?.status
        )

        // Register and verify everything gets delivered
        val expectedDBConfig = smartConfigFactory.create(ConfigFactory.parseString(DB_CONFIG_STRING))
        val expectedKeys = mutableSetOf(BOOT_CONFIG, FLOW_CONFIG, DB_CONFIG)
        val expectedConfig = mutableMapOf(
            BOOT_CONFIG to bootConfig, FLOW_CONFIG to flowConfig, DB_CONFIG to expectedDBConfig
        )
        var receivedKeys = emptySet<String>()
        var receivedConfig = mapOf<String, SmartConfig>()
        val reg = configurationReadService.registerForUpdates { keys, config ->
            receivedKeys = keys
            receivedConfig = config
            latch.countDown()
        }
        latch.await(TIMEOUT, TimeUnit.MILLISECONDS)
        assertEquals(expectedKeys, receivedKeys, "Incorrect keys")
        assertEquals(expectedConfig[BOOT_CONFIG], receivedConfig[BOOT_CONFIG], "Incorrect config")
        assertEquals(expectedConfig[FLOW_CONFIG], receivedConfig[FLOW_CONFIG], "Incorrect config")
        assertEquals(expectedConfig[DB_CONFIG], receivedConfig[DB_CONFIG], "Incorrect config")

        // Cleanup
        reg.close()
        reg1.close()
        publisher.close()
        configurationReadService.close()
    }
}
