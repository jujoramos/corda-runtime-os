package net.corda.flow.application.persistence.external.events

import java.nio.ByteBuffer
import net.corda.data.persistence.MergeEntities
import net.corda.flow.external.events.factory.ExternalEventFactory
import org.osgi.service.component.annotations.Component

@Component(service = [ExternalEventFactory::class])
class MergeExternalEventFactory : AbstractPersistenceExternalEventFactory<MergeParameters>() {

    override fun createRequest(parameters: MergeParameters): Any {
        return MergeEntities(listOf(parameters.serializedEntity))
    }
}

data class MergeParameters(val serializedEntity: ByteBuffer)