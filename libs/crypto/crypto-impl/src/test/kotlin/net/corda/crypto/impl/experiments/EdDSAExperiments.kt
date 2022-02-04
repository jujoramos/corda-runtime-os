package net.corda.crypto.impl.experiments

import net.corda.test.util.createTestCase
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.apache.commons.lang3.time.StopWatch
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jcajce.spec.EdDSAParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemReader
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertTrue

class EdDSAExperiments {
    companion object {
        private val clearData: ByteArray = Random(Instant.now().toEpochMilli()).nextBytes(347)
        private const val concurrentThreads = 10
        private const val iterations: Int = 100_000
        private lateinit var i2p: TestCase
        private lateinit var bc: TestCase

        @JvmStatic
        @BeforeAll
        fun setup() {
            i2p = TestCase(
                provider = EdDSASecurityProvider(),
                keyAlgorithmName = "1.3.101.112",
                keySpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519),
                signatureAlgorithmName = "NONEwithEdDSA"
            )
            bc = TestCase(
                provider = BouncyCastleProvider(),
                keyAlgorithmName = "Ed25519",// "1.3.101.112",
                keySpec = EdDSAParameterSpec(EdDSAParameterSpec.Ed25519),
                signatureAlgorithmName = "EdDSA"
            )
            // warming up
            i2p.run()
            i2p.deserialize(i2p.serializeAsByteArray(i2p.generateKeyPair().public))
            i2p.deserialize(i2p.serializeAsString(i2p.generateKeyPair().public))
            bc.run()
            bc.deserialize(bc.serializeAsByteArray(bc.generateKeyPair().public))
            bc.deserialize(bc.serializeAsString(bc.generateKeyPair().public))
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - generate, sign, verify`() {
        runWithStopwatch("net.i2p.crypto.eddsa - generate, sign, verify", i2p)
    }

    @Test
    fun `net_i2p_crypto_eddsa - sign`() {
        val keyPair = i2p.generateKeyPair()
        runWithStopwatch("net.i2p.crypto.eddsa - sign") {
            i2p.sign(keyPair.private)
            true
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - verify`() {
        val keyPair = i2p.generateKeyPair()
        val signature = i2p.sign(keyPair.private)
        runWithStopwatch("net.i2p.crypto.eddsa - verify") {
            i2p.verify(keyPair.public, signature)
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - round trip byte array serialization`() {
        val keyPair = i2p.generateKeyPair()
        runWithStopwatch("net.i2p.crypto.eddsa - round trip byte array serialization") {
            i2p.deserialize(i2p.serializeAsByteArray(keyPair.public))
            true
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - round trip PEM serialization`() {
        val keyPair = i2p.generateKeyPair()
        runWithStopwatch("net.i2p.crypto.eddsa - round trip PEM serialization") {
            i2p.deserialize(i2p.serializeAsString(keyPair.public))
            true
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - CONCURRENT generate, sign, verify`() {
        runConcurrentlyWithStopwatch("net.i2p.crypto.eddsa - CONCURRENT generate, sign, verify") {
            i2p.run()
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - CONCURRENT round trip byte array serialization`() {
        val keyPair = i2p.generateKeyPair()
        runConcurrentlyWithStopwatch("net.i2p.crypto.eddsa - CONCURRENT round trip byte array serialization") {
            i2p.deserialize(i2p.serializeAsByteArray(keyPair.public))
            true
        }
    }

    @Test
    fun `net_i2p_crypto_eddsa - CONCURRENT round trip PEM serialization`() {
        val keyPair = i2p.generateKeyPair()
        runConcurrentlyWithStopwatch("net.i2p.crypto.eddsa - CONCURRENT round trip PEM serialization") {
            i2p.deserialize(i2p.serializeAsString(keyPair.public))
            true
        }
    }

    @Test
    fun `Bouncy Castle - generate, sign, verify`() {
        runWithStopwatch("Bouncy Castle - generate, sign, verify", bc)
    }

    @Test
    fun `Bouncy Castle - sign`() {
        val keyPair = bc.generateKeyPair()
        runWithStopwatch("Bouncy Castle - sign") {
            bc.sign(keyPair.private)
            true
        }
    }

    @Test
    fun `Bouncy Castle - verify`() {
        val keyPair = bc.generateKeyPair()
        val signature = bc.sign(keyPair.private)
        runWithStopwatch("Bouncy Castle - verify") {
            bc.verify(keyPair.public, signature)
        }
    }

    @Test
    fun `Bouncy Castle - round trip byte array serialization`() {
        val keyPair = bc.generateKeyPair()
        runWithStopwatch("Bouncy Castle - round trip byte array serialization") {
            bc.deserialize(bc.serializeAsByteArray(keyPair.public))
            true
        }
    }

    @Test
    fun `Bouncy Castle - round trip PEM serialization`() {
        val keyPair = bc.generateKeyPair()
        runWithStopwatch("Bouncy Castle - round trip PEM serialization") {
            bc.deserialize(bc.serializeAsString(keyPair.public))
            true
        }
    }

    @Test
    fun `Bouncy Castle - CONCURRENT generate, sign, verify`() {
        runConcurrentlyWithStopwatch("Bouncy Castle - CONCURRENT generate, sign, verify") {
            bc.run()
        }
    }

    @Test
    fun `Bouncy Castle - CONCURRENT round trip byte array serialization`() {
        val keyPair = bc.generateKeyPair()
        runConcurrentlyWithStopwatch("Bouncy Castle - CONCURRENT round trip byte array serialization") {
            bc.deserialize(bc.serializeAsByteArray(keyPair.public))
            true
        }
    }

    @Test
    fun `Bouncy Castle - CONCURRENT round trip PEM serialization`() {
        val keyPair = bc.generateKeyPair()
        runConcurrentlyWithStopwatch("Bouncy Castle - CONCURRENT round trip PEM serialization") {
            bc.deserialize(bc.serializeAsString(keyPair.public))
            true
        }
    }
    @Test
    fun `Cross provider signing and validation`() {
        val bcPair = bc.generateKeyPair()
        val i2pPair = i2p.generateKeyPair()

        //val bcPublicAsi2p = net.i2p.crypto.eddsa.EdDSAPublicKey(X509EncodedKeySpec(bcPair.public.encoded))
        val bcPublicAsi2p = i2p.keyFactory.generatePublic(X509EncodedKeySpec(bcPair.public.encoded))
        val i2pPublicAsBc = bc.keyFactory.generatePublic(X509EncodedKeySpec(i2pPair.public.encoded))

        // Can sign by BC and verify by i2p
        val sig3 = bc.sign(bcPair.private)
        assertTrue(i2p.verify(bcPublicAsi2p, sig3))

        // Can sign by i2p and verify by BC
        val sig4 = i2p.sign(i2pPair.private)
        assertTrue(bc.verify(i2pPublicAsBc, sig4))
    }

    private fun runWithStopwatch(caption: String, testCase: TestCase) {
        val stopwatch = StopWatch()
        stopwatch.start()
        (1..iterations).forEach { _ ->
            val result = testCase.run()
            assertTrue(result)
        }
        stopwatch.stop()
        println("$caption -- ELAPSED(for $iterations): ${stopwatch.time} ms.")
    }

    private fun runWithStopwatch(caption: String, block: () -> Boolean) {
        val stopwatch = StopWatch()
        stopwatch.start()
        (1..iterations).forEach { _ ->
            val result = block()
            assertTrue(result)
        }
        stopwatch.stop()
        println("$caption -- ELAPSED(for $iterations): ${stopwatch.time} ms.")
    }

    private fun runConcurrentlyWithStopwatch(caption: String, block: () -> Boolean) {
        val case = (1..concurrentThreads).createTestCase(5_000, 5_000) {
            (1..(iterations / 10)).forEach { _ ->
                val result = block()
                assertTrue(result)
            }
        }
        val stopwatch = StopWatch()
        stopwatch.start()
        case.runAndValidate()
        stopwatch.stop()
        println("$caption -- ELAPSED(for $iterations done in $concurrentThreads threads): ${stopwatch.time} ms.")
    }

    private class TestCase(
        private val provider: Provider,
        private val keyAlgorithmName: String,
        private val keySpec: AlgorithmParameterSpec,
        private val signatureAlgorithmName: String,
    ) {
        private val signatureInstances = SignatureInstancesExp(provider)

        val keyFactory: KeyFactory = KeyFactory.getInstance(keyAlgorithmName, provider)

        fun run(): Boolean {
            val keyPair = generateKeyPair()
            val signature = sign(keyPair.private)
            return verify(keyPair.public, signature)
        }

        fun generateKeyPair(): KeyPair {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                keyAlgorithmName,
                provider
            )
            keyPairGenerator.initialize(keySpec)
            return keyPairGenerator.generateKeyPair()
        }

        fun sign(privateKey: PrivateKey): ByteArray {
            return signatureInstances.withSignature(signatureAlgorithmName) {
                it.initSign(privateKey)
                it.update(clearData)
                it.sign()
            }
        }

        fun verify(publicKey: PublicKey, signature: ByteArray): Boolean {
            return signatureInstances.withSignature(signatureAlgorithmName) {
                it.initVerify(publicKey)
                it.update(clearData)
                it.verify(signature)
            }
        }

        fun serializeAsByteArray(publicKey: PublicKey): ByteArray  = publicKey.encoded

        fun serializeAsString(publicKey: PublicKey): String =
            StringWriter().use { strWriter ->
                JcaPEMWriter(strWriter).use { pemWriter ->
                    pemWriter.writeObject(publicKey)
                }
                return strWriter.toString()
            }

        fun deserialize(bytes: ByteArray): PublicKey =
            keyFactory.generatePublic(X509EncodedKeySpec(bytes))

        fun deserialize(str: String): PublicKey {
            val bytes = StringReader(str).use { strReader ->
                PemReader(strReader).use { pemReader ->
                    pemReader.readPemObject().content
                }
            }
            val publicKeyInfo = SubjectPublicKeyInfo.getInstance(bytes)
            val converter = JcaPEMKeyConverter()
            converter.setProvider(provider)
            return converter.getPublicKey(publicKeyInfo)
        }
    }
}

