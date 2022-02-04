package net.corda.crypto.impl.experiments

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import net.corda.test.util.createTestCase
import org.apache.commons.lang3.time.StopWatch
import org.bouncycastle.jcajce.spec.EdDSAParameterSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import kotlin.test.assertNotNull

class SerialisationExperiments {
    companion object {
        private const val concurrentThreads: Int = 10
        private const val iterations: Int = 1_000_000
        private val jsonMapper = JsonMapper.builder().enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES).build()
        private val objectMapper = jsonMapper
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        private val serializers =
            mapOf<String, AlgorithmParameterSpecSerializer<out AlgorithmParameterSpec>>(
                PSSParameterSpec::class.java.name to PSSParameterSpecSerializer(),
                ECGenParameterSpec::class.java.name to DummyAlgorithmParameterSpecSerializer<ECGenParameterSpec>(),
                ECParameterSpec::class.java.name to DummyAlgorithmParameterSpecSerializer<ECParameterSpec>(),
                EdDSAParameterSpec::class.java.name to DummyAlgorithmParameterSpecSerializer<EdDSAParameterSpec>()
            )
        private val paramsClassName = PSSParameterSpec::class.java.name

        @JvmStatic
        @BeforeAll
        @Suppress("UNCHECKED_CAST")
        fun setup() {
            val signatureParams = PSSParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                32,
                1
            )
            val serializer = serializers.getValue(paramsClassName) as
                    AlgorithmParameterSpecSerializer<AlgorithmParameterSpec>
            val bytes = serializer.serialize(signatureParams)
            val restored = serializer.deserialize(bytes)
            assertNotNull(restored)
        }
    }

    @Test
    fun `ByteBuffer serialization - should round trip serialise and deserialize`() {
        run(PSSParameterSpecSerializer())
    }

    @Test
    fun `CONCURRENTLY ByteBuffer serialization - should round trip serialise and deserialize`() {
        runConcurrently(PSSParameterSpecSerializer())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `CONCURRENTLY ByteBuffer serialization by map - should round trip serialise and deserialize`() {
        val signatureParams = PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1
        )
        runConcurrentlyWithStopwatch("CONCURRENTLY run ByteBuffer serialization by map") {
            val serializer = serializers.getValue(paramsClassName) as
                    AlgorithmParameterSpecSerializer<AlgorithmParameterSpec>
            val bytes = serializer.serialize(signatureParams)
            val restored = serializer.deserialize(bytes)
            assertNotNull(restored)
        }
    }

    @Test
    fun `JSON serialization - should round trip serialise and deserialize`() {
        run(PSSParameterSpecSerializerJson())
    }

    @Test
    fun `CONCURRENTLY JSON serialization - should round trip serialise and deserialize`() {
        runConcurrently(PSSParameterSpecSerializerJson())
    }

    private fun run(serializer: AlgorithmParameterSpecSerializer<PSSParameterSpec>) {
        val signatureParams = PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1
        )
        runWithStopwatch("Run with ${serializer::class.simpleName}") {
            val bytes = serializer.serialize(signatureParams)
            val restored = serializer.deserialize(bytes)
            assertNotNull(restored)
        }
    }

    private fun runConcurrently(serializer: AlgorithmParameterSpecSerializer<PSSParameterSpec>) {
        val signatureParams = PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1
        )
        runConcurrentlyWithStopwatch("CONCURRENTLY run with ${serializer::class.simpleName}") {
            val bytes = serializer.serialize(signatureParams)
            val restored = serializer.deserialize(bytes)
            assertNotNull(restored)
        }
    }

    private fun runWithStopwatch(caption: String, block: () -> Unit) {
        val stopwatch = StopWatch()
        stopwatch.start()
        (1..iterations).forEach { _ ->
            block()
        }
        stopwatch.stop()
        println("$caption -- ELAPSED(for ${iterations}): ${stopwatch.time} ms.")
    }

    private fun runConcurrentlyWithStopwatch(caption: String, block: () -> Unit) {
        val case = (1..concurrentThreads).createTestCase(5_000, 5_000) {
            (1..(iterations / 10)).forEach { _ ->
                block()
            }
        }
        val stopwatch = StopWatch()
        stopwatch.start()
        case.runAndValidate()
        stopwatch.stop()
        println("$caption -- ELAPSED(for $iterations done in $concurrentThreads threads): ${stopwatch.time} ms.")
    }

    interface AlgorithmParameterSpecSerializer<T : AlgorithmParameterSpec> {
        fun serialize(params: T): ByteArray
        fun deserialize(bytes: ByteArray): T
    }

    class DummyAlgorithmParameterSpecSerializer<T: AlgorithmParameterSpec> : AlgorithmParameterSpecSerializer<T> {
        override fun serialize(params: T): ByteArray = throw NotImplementedError()
        override fun deserialize(bytes: ByteArray): T = throw NotImplementedError()
    }

    class PSSParameterSpecSerializerJson : AlgorithmParameterSpecSerializer<PSSParameterSpec> {

        private class PSSParameterSpecTwin(
            val digestAlgorithm: String,
            val mgfParameters: String,
            val saltLength: Int,
            val trailerField: Int
        )

        override fun serialize(params: PSSParameterSpec): ByteArray {
            require(params.mgfParameters is MGF1ParameterSpec) {
                "Supports only '${MGF1ParameterSpec::class.java}'"
            }
            return objectMapper.writeValueAsBytes(
                PSSParameterSpecTwin(
                    digestAlgorithm = params.digestAlgorithm,
                    mgfParameters = (params.mgfParameters as MGF1ParameterSpec).digestAlgorithm,
                    saltLength = params.saltLength,
                    trailerField = params.trailerField
                )
            )
        }

        override fun deserialize(bytes: ByteArray): PSSParameterSpec {
            val twin = objectMapper.readValue(bytes, PSSParameterSpecTwin::class.java)
            return PSSParameterSpec(
                twin.digestAlgorithm,
                PSSParameterSpecSerializer.MGF1,
                MGF1ParameterSpec(twin.mgfParameters),
                twin.saltLength,
                twin.trailerField
            )
        }
    }

    class PSSParameterSpecSerializer : AlgorithmParameterSpecSerializer<PSSParameterSpec> {
        companion object {
            const val MGF1 = "MGF1"
        }

        override fun serialize(params: PSSParameterSpec): ByteArray {
            require(params.mgfParameters is MGF1ParameterSpec) {
                "Supports only '${MGF1ParameterSpec::class.java}'"
            }
            val digestAlgorithm = params.digestAlgorithm.toByteArray()
            val mgfParameters = (params.mgfParameters as MGF1ParameterSpec).digestAlgorithm.toByteArray()
            val buffer = ByteBuffer.allocate(
                4 + digestAlgorithm.size +
                        4 + mgfParameters.size +
                        4 +
                        4
            )
            buffer.putInt(digestAlgorithm.size)
            buffer.put(digestAlgorithm)
            buffer.putInt(mgfParameters.size)
            buffer.put(mgfParameters)
            buffer.putInt(params.saltLength)
            buffer.putInt(params.trailerField)
            return buffer.array()
        }

        override fun deserialize(bytes: ByteArray): PSSParameterSpec {
            val buffer = ByteBuffer.wrap(bytes)
            val digestAlgorithm = ByteArray(buffer.int)
            buffer.get(digestAlgorithm)
            val mgfParameters = ByteArray(buffer.int)
            buffer.get(mgfParameters)
            val saltLength = buffer.int
            val trailerField = buffer.int
            return PSSParameterSpec(
                String(digestAlgorithm),
                MGF1,
                MGF1ParameterSpec(String(mgfParameters)),
                saltLength,
                trailerField
            )
        }
    }
}