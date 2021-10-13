package net.corda.kryoserialization.factory

import net.corda.crypto.CryptoLibraryFactory
import net.corda.kryoserialization.impl.KryoCheckpointSerializerBuilderImpl
import net.corda.sandbox.SandboxGroup
import net.corda.serialization.CheckpointSerializerBuilder
import net.corda.serialization.factory.CheckpointSerializerBuilderFactory
import net.corda.v5.crypto.DigestAlgorithmName
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import java.security.Security

@Component(immediate = true, service = [CheckpointSerializerBuilderFactory::class])
class CheckpointSerializerBuilderFactoryImpl @Activate constructor(
    @Reference
    private val cryptoLibraryFactory: CryptoLibraryFactory
) : CheckpointSerializerBuilderFactory {
    override fun createCheckpointSerializerBuilder(
        sandboxGroup: SandboxGroup
    ): CheckpointSerializerBuilder {
        //Security.addProvider(BouncyCastleProvider())
        val digest = cryptoLibraryFactory.getDigestService()
        digest.hash("hello".toByteArray(), DigestAlgorithmName.SHA2_256)
        return KryoCheckpointSerializerBuilderImpl(
            cryptoLibraryFactory.getKeyEncodingService(),
            sandboxGroup
        )
    }
}
