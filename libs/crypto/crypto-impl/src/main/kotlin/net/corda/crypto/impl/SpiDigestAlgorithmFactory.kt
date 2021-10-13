package net.corda.crypto.impl

import net.corda.v5.cipher.suite.CipherSchemeMetadata
import net.corda.v5.cipher.suite.DigestAlgorithm
import net.corda.v5.cipher.suite.DigestAlgorithmFactory
import org.bouncycastle.jcajce.provider.digest.SHA256
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider

class SpiDigestAlgorithmFactory(
    schemeMetadata: CipherSchemeMetadata,
    override val algorithm: String,
) : DigestAlgorithmFactory {
    companion object {
        const val STREAM_BUFFER_SIZE = DEFAULT_BUFFER_SIZE
    }

    private val provider: Provider = schemeMetadata.providers.getValue(
        schemeMetadata.digests.firstOrNull { it.algorithmName == algorithm }?.providerName
            ?: throw IllegalArgumentException("Unknown hash algorithm $algorithm")
    )

    override fun getInstance(): DigestAlgorithm {
        try {
            val messageDigest = MessageDigest.getInstance(algorithm, provider)
            println(printClassLoader(provider::class.java.classLoader))
            println(printClassLoader(this::class.java.classLoader))
            return MessageDigestWrapper(messageDigest, algorithm)
        } catch (e: NoSuchAlgorithmException) {
            //throw IllegalArgumentException("Unknown hash algorithm $algorithm")

            throw IllegalArgumentException(
                "Unknown hash algorithm '$algorithm' for provider '${provider.name}'," + System.lineSeparator() +
                        "Provider's class loader defined packages" + System.lineSeparator() +
                        printClassLoader(provider::class.java.classLoader) + System.lineSeparator() +
                        "Current class loader defined packages" + System.lineSeparator() +
                        printClassLoader(this::class.java.classLoader) + System.lineSeparator() +
                        "Supported MessageDigest" + System.lineSeparator() +
                        provider.services
                            .filter { it.type == "MessageDigest" }
                            .joinToString(System.lineSeparator()) {
                                "type='${it.type}, algorithm=${it.algorithm}"
                            },
                e
            )
        }
    }

    private fun printClassLoader(classLoader: ClassLoader): String {
        var str = "name=${classLoader.name}${System.lineSeparator()}"
        str += classLoader.definedPackages.joinToString(System.lineSeparator()) { "package=${it.name}" }
        if (classLoader.parent != null) {
            str += "------------"
            str += printClassLoader(classLoader.parent)
        }
        try {
            val cls = classLoader.loadClass(SHA256.Digest::class.java.name)
            str += System.lineSeparator() + "LOADED: ${cls.name}"
        } catch (e: Throwable) {
            str += System.lineSeparator() + "FAILED TO LOAD: ${SHA256.Digest::class.java.name}"
        }
        return str
    }

    private class MessageDigestWrapper(
        val messageDigest: MessageDigest,
        override val algorithm: String
    ) : DigestAlgorithm {
        override val digestLength = messageDigest.digestLength
        override fun digest(bytes: ByteArray): ByteArray = messageDigest.digest(bytes)
        override fun digest(inputStream: InputStream): ByteArray {
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) break
                messageDigest.update(buffer, 0, read)
            }
            return messageDigest.digest()
        }
    }
}

