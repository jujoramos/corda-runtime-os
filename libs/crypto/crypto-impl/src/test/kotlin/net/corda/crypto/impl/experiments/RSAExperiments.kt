package net.corda.crypto.impl.experiments

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.time.Instant
import kotlin.random.Random


class RSAExperiments {
    companion object {
        private val clearData: ByteArray = Random(Instant.now().toEpochMilli()).nextBytes(347)
    }

    @Test
    fun `Should round trip generate key, sign and verify signature`() {
        val case = TestCase(
            signatureAlgorithmName = "RSASSA-PSS",
            signatureParams = PSSParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                32,
                1)
        )
        //val case = TestCase(signatureParams = null, signatureAlgorithmName = "SHA512withRSA/PSS")
        //val case = TestCase()
        val keyPair = case.generateKeyPair()
        val signature = case.sign(keyPair.private)
        case.verify(keyPair.public, signature)
    }

    class TestCase(
        private val provider: Provider = BouncyCastleProvider(),
        private val keyAlgorithmName: String = "RSA",
        private val signatureAlgorithmName: String = "SHA256withRSA/PSS",
        val signatureParams: AlgorithmParameterSpec? = PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1)
    ) {
        private val signatureInstances = SignatureInstancesExp(provider)

        val keyFactory: KeyFactory = KeyFactory.getInstance(keyAlgorithmName, provider)

        fun generateKeyPair(): KeyPair {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                keyAlgorithmName,
                provider
            )
            return keyPairGenerator.generateKeyPair()
        }

        fun sign(privateKey: PrivateKey): ByteArray {
            return signatureInstances.withSignature(signatureAlgorithmName) {
                if(signatureParams != null) {
                    it.setParameter(signatureParams)
                }
                it.initSign(privateKey)
                it.update(clearData)
                it.sign()
            }
        }

        fun verify(publicKey: PublicKey, signature: ByteArray): Boolean {
            return signatureInstances.withSignature(signatureAlgorithmName) {
                if(signatureParams != null) {
                    it.setParameter(signatureParams)
                }
                it.initVerify(publicKey)
                it.update(clearData)
                it.verify(signature)
            }
        }
    }
}