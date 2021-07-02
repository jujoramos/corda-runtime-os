package net.corda.serialization.impl

import net.corda.serialization.CheckpointSerializationContext
import net.corda.v5.serialization.CheckpointCustomSerializer
import net.corda.v5.serialization.ClassWhitelist
import net.corda.v5.serialization.EncodingWhitelist
import net.corda.v5.serialization.SerializationEncoding
import org.osgi.framework.Bundle

data class CheckpointSerializationContextImpl @JvmOverloads constructor(
        override val deserializationClassLoader: ClassLoader,
        override val whitelist: ClassWhitelist,
        override val properties: Map<Any, Any>,
        override val objectReferencesEnabled: Boolean,
        override val encoding: SerializationEncoding?,
        override val encodingWhitelist: EncodingWhitelist = NullEncodingWhitelist,
        override val checkpointCustomSerializers: Iterable<CheckpointCustomSerializer<*, *>> = emptyList(),
        override val bundles: List<Bundle> = emptyList()
) : CheckpointSerializationContext {

    override fun withProperty(property: Any, value: Any): CheckpointSerializationContext {
        return copy(properties = properties + (property to value))
    }

    override fun withoutReferences(): CheckpointSerializationContext {
        return copy(objectReferencesEnabled = false)
    }

    override fun withClassLoader(classLoader: ClassLoader): CheckpointSerializationContext {
        return copy(deserializationClassLoader = classLoader)
    }

    override fun withBundles(bundles: List<Bundle>): CheckpointSerializationContext {
        return copy(bundles = bundles)
    }

    override fun withWhitelisted(clazz: Class<*>): CheckpointSerializationContext {
        return copy(whitelist = object : ClassWhitelist {
            override fun hasListed(type: Class<*>): Boolean = whitelist.hasListed(type) || type.name == clazz.name
        })
    }

    override fun withEncoding(encoding: SerializationEncoding?) = copy(encoding = encoding)
    override fun withEncodingWhitelist(encodingWhitelist: EncodingWhitelist) = copy(encodingWhitelist = encodingWhitelist)
    override fun withCheckpointCustomSerializers(checkpointCustomSerializers : Iterable<CheckpointCustomSerializer<*,*>>)
            = copy(checkpointCustomSerializers = checkpointCustomSerializers)
}
