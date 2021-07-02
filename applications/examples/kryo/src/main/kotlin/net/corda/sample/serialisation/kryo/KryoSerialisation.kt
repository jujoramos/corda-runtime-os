package net.corda.sample.serialisation.kryo

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.util.MapReferenceResolver
import net.corda.kryoserialization.CheckpointSerializeAsTokenContextImpl
import net.corda.kryoserialization.KryoCheckpointSerializerBuilder
import net.corda.kryoserialization.osgi.OsgiClassResolver
import net.corda.osgi.api.Application
import net.corda.osgi.api.Shutdown
import net.corda.serialization.CheckpointSerializationContext
import net.corda.serialization.CheckpointSerializer
import net.corda.serialization.DefaultWhitelist
import net.corda.serialization.KRYO_CHECKPOINT_CONTEXT
import net.corda.v5.base.util.contextLogger
import net.corda.v5.serialization.SerializationToken
import net.corda.v5.serialization.SerializeAsToken
import net.corda.v5.serialization.SerializeAsTokenContext
import org.objenesis.strategy.SerializingInstantiatorStrategy
import org.osgi.framework.BundleContext
import org.osgi.framework.FrameworkUtil
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger

@Component(immediate = true)
class KryoSerialisation @Activate constructor(
    @Reference(service = Shutdown::class)
    private var shutdown: Shutdown
) : Application {

    private val service = MyService()
    private val serializer = createCheckpointSerializer()
    private val context = createCheckpointContext()

    private companion object {
        private val logger: Logger = contextLogger()
    }

    override fun startup(args: Array<String>) {
        val bytes = serializer.serialize(service, context)
        val output = serializer.deserialize(bytes, MyService::class.java, context)

        logger.info(service.toString())
        logger.info(output.toString())
    }

    private fun createCheckpointSerializer(): CheckpointSerializer {
        val kryo = Kryo(OsgiClassResolver(emptyList()) { null }, MapReferenceResolver())
        kryo.instantiatorStrategy = SerializingInstantiatorStrategy()
        val serializerBuilder = KryoCheckpointSerializerBuilder({ kryo }, KryoSerialisation::class, DefaultWhitelist)
        return serializerBuilder.build()
    }

    private fun createCheckpointContext(): CheckpointSerializationContext {
        return KRYO_CHECKPOINT_CONTEXT.withTokenContext(
            CheckpointSerializeAsTokenContextImpl(
                listOf(service),
                serializer,
                KRYO_CHECKPOINT_CONTEXT
            )
        )
    }

    override fun shutdown() {
        logger.info("Shutting down config reader")
    }

    private fun shutdownOSGiFramework() {
        val bundleContext: BundleContext? = FrameworkUtil.getBundle(KryoSerialisation::class.java).bundleContext
        if (bundleContext != null) {
            shutdown.shutdown(bundleContext.bundle)
        }
    }


    private class MySerializationToken(val name: String) : SerializationToken {
        override fun fromToken(context: SerializeAsTokenContext): Any {
            return context.fromIdentifier(name)
        }
    }

    private class MyService : SerializeAsToken {
        var count = 0
        val token = MySerializationToken("hello there")

        override fun toToken(context: SerializeAsTokenContext): SerializationToken {
            count++
            context.withIdentifier(token.name, this)
            return token
        }
    }
}
