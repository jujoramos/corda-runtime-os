package net.corda.crypto.merkle.impl

import net.corda.cipher.suite.impl.CipherSchemeMetadataImpl
import net.corda.cipher.suite.impl.DigestServiceImpl
import net.corda.v5.cipher.suite.CipherSchemeMetadata
import net.corda.v5.cipher.suite.DigestService
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertArrayEquals

class SecureHashSerializationTests {
    companion object {
        private lateinit var digestService: DigestService

        @BeforeAll
        @JvmStatic
        fun setup() {
            val schemeMetadata: CipherSchemeMetadata = CipherSchemeMetadataImpl()
            digestService = DigestServiceImpl(schemeMetadata, null)
        }
    }

    @Test
    fun `serialize should return with a bytearray of the SecureHash`() {
        val data = "abc".toByteArray()
        val algorithm = DigestAlgorithmName.SHA2_256.name
        val digest = MessageDigest.getInstance(algorithm).digest(data)
        val cut = SecureHash(
            algorithm = algorithm,
            bytes = digest
        )

        val expected = byteArrayOf(83, 72, 65, 45, 50, 53, 54, // SHA-256
            58, // :
            -70, 120, 22, -65, -113, 1, -49, -22, 65, 65, 64, -34, 93, -82, 34, 35, // The digest
            -80, 3, 97, -93, -106, 23, 122, -100, -76, 16, -1, 97, -14, 0, 21, -83)

        assertArrayEquals(expected, cut.serialize())
    }

    @Test
    fun `deserialize should restore original value`() {
        val data = "abc".toByteArray()
        val algorithm = DigestAlgorithmName.SHA2_256.name
        val digest = MessageDigest.getInstance(algorithm).digest(data)
        val cut = SecureHash(
            algorithm = algorithm,
            bytes = digest
        )

        val sha256serialized = byteArrayOf(83, 72, 65, 45, 50, 53, 54,
            58,
            -70, 120, 22, -65, -113, 1, -49, -22, 65, 65, 64, -34, 93, -82, 34, 35,
            -80, 3, 97, -93, -106, 23, 122, -100, -76, 16, -1, 97, -14, 0, 21, -83)
        val deserialized = SecureHash.deserialize(sha256serialized, digestService)
        assertArrayEquals(cut.bytes, deserialized.bytes)
        assertEquals(cut.algorithm, deserialized.algorithm)
        assertEquals(cut, deserialized)
    }

    @Test
    fun `deserialize should throw IllegalArgumentException when the serialized bytes have tampered - no delimiter`() {
        val sha256serialized = byteArrayOf(83, 72, 65, 45, 50, 53, 54,
            -70, 120, 22, -65, -113, 1, -49, -22, 65, 65, 64, -34, 93, -82, 34, 35,
            -80, 3, 97, -93, -106, 23, 122, -100, -76, 16, -1, 97, -14, 0, 21, -83)
        assertThrows(IllegalArgumentException::class.java) {
            SecureHash.deserialize(sha256serialized, digestService)
        }
    }

    @Test
    fun `deserialize should throw IllegalArgumentException when the serialized bytes have tampered - non existing algorithm`() {
        val sha256serialized = byteArrayOf(72, 65, 45, 50, 53, 54,
            58,
            -70, 120, 22, -65, -113, 1, -49, -22, 65, 65, 64, -34, 93, -82, 34, 35,
            -80, 3, 97, -93, -106, 23, 122, -100, -76, 16, -1, 97, -14, 0, 21, -83)
        assertThrows(IllegalArgumentException::class.java) {
            SecureHash.deserialize(sha256serialized, digestService)
        }
    }

    @Test
    fun `deserialize should throw IllegalArgumentException when the serialized bytes have tampered - too short`() {
        val sha256serialized = byteArrayOf(83, 72, 65, 45, 50, 53, 54,
            58,
            -70, 120, 22, -65, -113, 1, -49, -22, 65, 65, 64, -34, 93, -82, 34, 35,
            -80, 3, 97, -93, -106, 23, 122, -100, -76, 16, -1, 97, -14, 0, 21)
        assertThrows(IllegalArgumentException::class.java) {
            SecureHash.deserialize(sha256serialized, digestService)
        }
    }

    @Test
    fun `deserialize should throw IllegalArgumentException when the serialized bytes have tampered - too long`() {
        val sha256serialized = byteArrayOf(83, 72, 65, 45, 50, 53, 54,
            58,
            -70, 120, 22, -65, -113, 1, -49, -22, 65, 65, 64, -34, 93, -82, 34, 35,
            -80, 3, 97, -93, -106, 23, 122, -100, -76, 16, -1, 97, -14, 0, 21, -83, -83)
        assertThrows(IllegalArgumentException::class.java) {
            SecureHash.deserialize(sha256serialized, digestService)
        }
    }
}
