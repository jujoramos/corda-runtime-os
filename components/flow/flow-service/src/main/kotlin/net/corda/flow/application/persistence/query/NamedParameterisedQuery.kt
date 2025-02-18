package net.corda.flow.application.persistence.query

import co.paralleluniverse.fibers.Suspendable
import java.nio.ByteBuffer
import net.corda.flow.application.persistence.external.events.NamedQueryExternalEventFactory
import net.corda.flow.application.persistence.external.events.NamedQueryParameters
import net.corda.flow.application.persistence.wrapWithPersistenceException
import net.corda.flow.external.events.executor.ExternalEventExecutor
import net.corda.flow.fiber.FlowFiberSerializationService
import net.corda.v5.application.persistence.ParameterisedQuery

/**
 * [NamedParameterisedQuery] is used to set and execute named queries.
 */
@Suppress("LongParameterList")
class NamedParameterisedQuery<R : Any>(
    private val externalEventExecutor: ExternalEventExecutor,
    private val flowFiberSerializationService: FlowFiberSerializationService,
    private val queryName: String,
    private var parameters: MutableMap<String, Any>,
    private var limit: Int,
    private var offset: Int,
    private var expectedClass: Class<R>,
) : ParameterisedQuery<R> {

    override fun setLimit(limit: Int): ParameterisedQuery<R> {
        require (limit >= 0) { "Limit cannot be negative" }
        this.limit = limit
        return this
    }

    override fun setOffset(offset: Int): ParameterisedQuery<R> {
        require (offset >= 0) { "Offset cannot be negative" }
        this.offset = offset
        return this
    }

    override fun setParameter(name: String, value: Any): ParameterisedQuery<R> {
        parameters[name] = value
        return this
    }

    override fun setParameters(parameters: Map<String, Any>): ParameterisedQuery<R> {
        this.parameters = parameters.toMutableMap()
        return this
    }

    @Suspendable
    override fun execute(): List<R> {
        val deserialized = wrapWithPersistenceException {
            externalEventExecutor.execute(
                NamedQueryExternalEventFactory::class.java,
                NamedQueryParameters(queryName, getSerializedParameters(parameters), offset, limit)
            )
        }.map { flowFiberSerializationService.deserialize(it.array(), expectedClass) }

        return deserialized
    }

    @Suspendable
    private fun getSerializedParameters(parameters: Map<String, Any>) : Map<String, ByteBuffer> {
        return parameters.mapValues {
            ByteBuffer.wrap(flowFiberSerializationService.serialize(it.value).bytes)
        }
    }
}