package net.corda.testing.sandboxes.impl

import net.corda.cpiinfo.read.CpiInfoListener
import net.corda.cpiinfo.read.CpiInfoReadService
import net.corda.libs.packaging.core.CpiIdentifier
import net.corda.libs.packaging.core.CpiMetadata
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.reconciliation.VersionedRecord
import net.corda.testing.sandboxes.CpiLoader
import net.corda.v5.base.util.loggerFor
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import java.util.stream.Stream

@Suppress("unused")
@Component
class CpiInfoServiceImpl @Activate constructor(
    @Reference
    private val loader: CpiLoader
): CpiInfoReadService {
    private val logger = loggerFor<CpiInfoServiceImpl>()

    override val lifecycleCoordinatorName = LifecycleCoordinatorName.forComponent<CpiInfoReadService>()

    override fun getAllVersionedRecords(): Stream<VersionedRecord<CpiIdentifier, CpiMetadata>> =
        getAll().stream().map {
            VersionedRecord(
                version = it.version,
                isDeleted = false,
                key = it.cpiId,
                value = it
            )
        }

    override val isRunning: Boolean
        get() = true

    override fun getAll(): List<CpiMetadata> {
        val cpiList = loader.getAllCpiMetadata()
        return cpiList.get()
    }

    override fun get(identifier: CpiIdentifier): CpiMetadata? {
        val cpiFile = loader.getCpiMetadata(identifier)
        return cpiFile.get()
    }

    override fun registerCallback(listener: CpiInfoListener): AutoCloseable {
        return AutoCloseable {}
    }

    override fun start() {
        logger.info("Started")
    }

    override fun stop() {
        logger.info("Stopped")
    }
}
