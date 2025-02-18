package net.corda.applications.workers.p2p.gateway

import net.corda.applications.workers.workercommon.DefaultWorkerParams
import net.corda.applications.workers.workercommon.HealthMonitor
import net.corda.applications.workers.workercommon.WorkerHelpers
import net.corda.libs.configuration.validation.ConfigurationValidatorFactory
import net.corda.osgi.api.Application
import net.corda.osgi.api.Shutdown
import net.corda.processors.p2p.gateway.GatewayProcessor
import net.corda.v5.base.util.contextLogger
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import picocli.CommandLine

@Component
@Suppress("LongParameterList")
class GatewayWorker @Activate constructor(
    @Reference(service = Shutdown::class)
    private val shutDownService: Shutdown,
    @Reference(service = GatewayProcessor::class)
    private val gatewayProcessor: GatewayProcessor,
    @Reference(service = HealthMonitor::class)
    private val healthMonitor: HealthMonitor,
    @Reference(service = ConfigurationValidatorFactory::class)
    private val configurationValidatorFactory: ConfigurationValidatorFactory,
) : Application {

    private companion object {
        private val logger = contextLogger()
    }

    override fun startup(args: Array<String>) {
        logger.info("P2P Gateway worker starting.")

        val params = WorkerHelpers.getParams(args, GatewayWorkerParams())
        if (WorkerHelpers.printHelpOrVersion(params.defaultParams, this::class.java, shutDownService)) return
        WorkerHelpers.setUpHealthMonitor(healthMonitor, params.defaultParams)

        val config = WorkerHelpers.getBootstrapConfig(
            params.defaultParams,
            configurationValidatorFactory.createConfigValidator()
        )

        gatewayProcessor.start(config, !params.withoutStubs)
    }

    override fun shutdown() {
        logger.info("P2P Gateway worker stopping.")
        gatewayProcessor.stop()
        healthMonitor.stop()
    }
}
/** Additional parameters for the member worker are added here. */
private class GatewayWorkerParams {
    @CommandLine.Mixin
    var defaultParams = DefaultWorkerParams()

    //This is used to test the gateway without Crypto component. It will be removed in CORE-5782.
    @CommandLine.Option(names = ["--without-stubs"])
    var withoutStubs = false
}
