package net.corda.processors.crypto.tests

import com.typesafe.config.ConfigRenderOptions
import java.security.PublicKey
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.stream.Stream
import javax.persistence.EntityManagerFactory
import net.corda.crypto.client.CryptoOpsClient
import net.corda.crypto.client.hsm.HSMRegistrationClient
import net.corda.crypto.core.CryptoConsts
import net.corda.crypto.core.CryptoTenants
import net.corda.crypto.core.publicKeyIdFromBytes
import net.corda.crypto.ecies.EphemeralKeyPairEncryptor
import net.corda.crypto.ecies.StableKeyPairDecryptor
import net.corda.crypto.flow.CryptoFlowOpsTransformer
import net.corda.crypto.flow.factory.CryptoFlowOpsTransformerFactory
import net.corda.crypto.persistence.db.model.CryptoEntities
import net.corda.data.CordaAvroSerializationFactory
import net.corda.data.config.Configuration
import net.corda.data.config.ConfigurationSchemaVersion
import net.corda.data.crypto.wire.ops.flow.FlowOpsResponse
import net.corda.data.crypto.wire.ops.rpc.queries.CryptoKeyOrderBy
import net.corda.data.flow.event.external.ExternalEventContext
import net.corda.db.admin.LiquibaseSchemaMigrator
import net.corda.db.connection.manager.VirtualNodeDbType
import net.corda.db.core.DbPrivilege
import net.corda.db.messagebus.testkit.DBSetup
import net.corda.db.schema.CordaDb
import net.corda.db.schema.DbSchema
import net.corda.db.testkit.DatabaseInstaller
import net.corda.db.testkit.TestDbInfo
import net.corda.libs.configuration.datamodel.ConfigurationEntities
import net.corda.libs.configuration.datamodel.DbConnectionConfig
import net.corda.libs.packaging.core.CpiIdentifier
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.registry.LifecycleRegistry
import net.corda.messaging.api.publisher.Publisher
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.messaging.api.records.Record
import net.corda.messaging.api.subscription.factory.SubscriptionFactory
import net.corda.orm.EntityManagerFactoryFactory
import net.corda.orm.JpaEntitiesRegistry
import net.corda.orm.utils.transaction
import net.corda.processors.crypto.CryptoProcessor
import net.corda.processors.crypto.tests.infra.TestDependenciesTracker
import net.corda.processors.crypto.tests.infra.FlowOpsResponses
import net.corda.processors.crypto.tests.infra.RESPONSE_TOPIC
import net.corda.processors.crypto.tests.infra.makeBootstrapConfig
import net.corda.processors.crypto.tests.infra.makeClientId
import net.corda.processors.crypto.tests.infra.makeMessagingConfig
import net.corda.processors.crypto.tests.infra.publishVirtualNodeInfo
import net.corda.processors.crypto.tests.infra.randomDataByteArray
import net.corda.processors.crypto.tests.infra.startAndWait
import net.corda.schema.Schemas.Config.Companion.CONFIG_TOPIC
import net.corda.schema.Schemas.Crypto.Companion.FLOW_OPS_MESSAGE_TOPIC
import net.corda.schema.configuration.BootConfig.BOOT_DB_PARAMS
import net.corda.schema.configuration.ConfigKeys.MESSAGING_CONFIG
import net.corda.test.util.eventually
import net.corda.test.util.identity.createTestHoldingIdentity
import net.corda.v5.base.util.contextLogger
import net.corda.v5.cipher.suite.CipherSchemeMetadata
import net.corda.v5.cipher.suite.SignatureVerificationService
import net.corda.v5.crypto.DigitalSignature
import net.corda.v5.crypto.ECDSA_SECP256R1_CODE_NAME
import net.corda.v5.crypto.SignatureSpec
import net.corda.v5.crypto.X25519_CODE_NAME
import net.corda.v5.crypto.publicKeyId
import net.corda.virtualnode.VirtualNodeInfo
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import org.bouncycastle.jcajce.provider.util.DigestFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.osgi.test.common.annotation.InjectService
import org.osgi.test.junit5.service.ServiceExtension

@ExtendWith(ServiceExtension::class, DBSetup::class)
class CryptoProcessorTests {
    companion object {
        private val logger = contextLogger()

        private val CLIENT_ID = makeClientId<CryptoProcessorTests>()

        @InjectService(timeout = 5000L)
        lateinit var lifecycleRegistry: LifecycleRegistry

        @InjectService(timeout = 5000L)
        lateinit var coordinatorFactory: LifecycleCoordinatorFactory

        @InjectService(timeout = 5000L)
        lateinit var publisherFactory: PublisherFactory

        @InjectService(timeout = 5000L)
        lateinit var subscriptionFactory: SubscriptionFactory

        @InjectService(timeout = 5000L)
        lateinit var ephemeralEncryptor: EphemeralKeyPairEncryptor

        @InjectService(timeout = 5000L)
        lateinit var stableDecryptor: StableKeyPairDecryptor

        @InjectService(timeout = 5000L)
        lateinit var cryptoFlowOpsTransformerFactory: CryptoFlowOpsTransformerFactory

        @InjectService(timeout = 5000L)
        lateinit var opsClient: CryptoOpsClient

        @InjectService(timeout = 5000L)
        lateinit var verifier: SignatureVerificationService

        @InjectService(timeout = 5000L)
        lateinit var schemeMetadata: CipherSchemeMetadata

        @InjectService(timeout = 5000)
        lateinit var entitiesRegistry: JpaEntitiesRegistry

        @InjectService(timeout = 5000)
        lateinit var entityManagerFactoryFactory: EntityManagerFactoryFactory

        @InjectService(timeout = 5000L)
        lateinit var hsmRegistrationClient: HSMRegistrationClient

        @InjectService(timeout = 5000)
        lateinit var lbm: LiquibaseSchemaMigrator

        @InjectService(timeout = 5000)
        lateinit var virtualNodeInfoReader: VirtualNodeInfoReadService

        @InjectService(timeout = 5000L)
        lateinit var cryptoProcessor: CryptoProcessor

        @InjectService(timeout = 5000L)
        lateinit var cordaAvroSerializationFactory: CordaAvroSerializationFactory

        private lateinit var publisher: Publisher

        private lateinit var flowOpsResponses: FlowOpsResponses

        private lateinit var transformer: CryptoFlowOpsTransformer

        private val vnodeIdentity =
            createTestHoldingIdentity("CN=Alice, O=Alice Corp, L=LDN, C=GB", UUID.randomUUID().toString())

        private val vnodeId: String = vnodeIdentity.shortHash.value

        private val clusterDb = TestDbInfo.createConfig()

        private val cryptoDb = TestDbInfo(
            name = CordaDb.Crypto.persistenceUnitName,
            schemaName = DbSchema.CRYPTO
        )

        private val vnodeDb = TestDbInfo(
            name = VirtualNodeDbType.CRYPTO.getConnectionName(vnodeIdentity.shortHash),
            schemaName = "vnode_crypto"
        )

        private lateinit var connectionIds: Map<String, UUID>

        private val boostrapConfig = makeBootstrapConfig(
            mapOf(
                BOOT_DB_PARAMS to clusterDb.config
            )
        )

        private val messagingConfig = makeMessagingConfig(boostrapConfig)

        @JvmStatic
        @BeforeAll
        fun setup() {
            setupPrerequisites()
            setupDatabases()
            setupVirtualNodeInfo()
            startDependencies()
            waitForVirtualNodeInfoReady()
            assignHSMs()
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            if (::flowOpsResponses.isInitialized) {
                flowOpsResponses.close()
            }
            cryptoProcessor.stop()
            eventually { assertFalse(cryptoProcessor.isRunning) }
        }

        private fun setupPrerequisites() {
            // Creating this publisher first (using the messagingConfig) will ensure we're forcing
            // the in-memory message bus. Otherwise we may attempt to use a real database for the test
            // and that can cause message bus conflicts when the tests are run in parallel.
            publisher = publisherFactory.createPublisher(PublisherConfig(CLIENT_ID), messagingConfig)
            logger.info("Publishing prerequisite config")
            publisher.publish(
                listOf(
                    Record(
                        CONFIG_TOPIC,
                        MESSAGING_CONFIG,
                        Configuration(
                            messagingConfig.root().render(),
                            messagingConfig.root().render(),
                            0,
                            ConfigurationSchemaVersion(1, 0)
                        )
                    )
                )
            )
            flowOpsResponses = FlowOpsResponses(
                messagingConfig,
                subscriptionFactory,
                cordaAvroSerializationFactory.createAvroDeserializer({}, FlowOpsResponse::class.java)
            )
            transformer = cryptoFlowOpsTransformerFactory.create(
                requestingComponent = "test",
                responseTopic = RESPONSE_TOPIC
            )
        }

        private fun setupDatabases() {
            val databaseInstaller = DatabaseInstaller(entityManagerFactoryFactory, lbm, entitiesRegistry)
            val configEmf = databaseInstaller.setupClusterDatabase(
                clusterDb,
                "config",
                ConfigurationEntities.classes
            )
            databaseInstaller.setupDatabase(
                cryptoDb,
                "crypto"
            ).close()
            databaseInstaller.setupDatabase(
                vnodeDb,
                "vnode-crypto",
                CryptoEntities.classes
            ).close()
            connectionIds = addDbConnectionConfigs(configEmf, cryptoDb, vnodeDb)
            configEmf.close()
        }

        private fun addDbConnectionConfigs(
            configEmf: EntityManagerFactory,
            vararg dbs: TestDbInfo
        ): Map<String, UUID> {
            val ids = mutableMapOf<String, UUID>()
            dbs.forEach { db ->
                val configAsString = db.config.root().render(ConfigRenderOptions.concise())
                configEmf.transaction {
                    val existing = it.createQuery(
                        """
                        SELECT c FROM DbConnectionConfig c WHERE c.name=:name AND c.privilege=:privilege
                    """.trimIndent(), DbConnectionConfig::class.java
                    )
                        .setParameter("name", db.name)
                        .setParameter("privilege", DbPrivilege.DML)
                        .resultList
                    ids[db.name] = if (existing.isEmpty()) {
                        val record = DbConnectionConfig(
                            UUID.randomUUID(),
                            db.name,
                            DbPrivilege.DML,
                            Instant.now(),
                            "sa",
                            "Test ${db.name}",
                            configAsString
                        )
                        it.persist(record)
                        record.id
                    } else {
                        existing.first().id
                    }
                }
            }
            return ids
        }

        private fun setupVirtualNodeInfo() {
            publisher.publishVirtualNodeInfo(
                VirtualNodeInfo(
                    holdingIdentity = vnodeIdentity,
                    cpiIdentifier = CpiIdentifier(
                        name = "cpi",
                        version = "1",
                        signerSummaryHash = null
                    ),
                    cryptoDmlConnectionId = connectionIds.getValue(vnodeDb.name),
                    vaultDmlConnectionId = UUID.randomUUID(),
                    timestamp = Instant.now()
                )
            )
        }

        private fun startDependencies() {
            hsmRegistrationClient.startAndWait()
            cryptoProcessor.startAndWait(boostrapConfig)
            stableDecryptor.startAndWait()
            val tracker = TestDependenciesTracker(
                LifecycleCoordinatorName.forComponent<CryptoProcessorTests>(),
                coordinatorFactory,
                lifecycleRegistry,
                setOf(
                    LifecycleCoordinatorName.forComponent<CryptoProcessor>(),
                    LifecycleCoordinatorName.forComponent<HSMRegistrationClient>(),
                    LifecycleCoordinatorName.forComponent<StableKeyPairDecryptor>()
                )
            ).also { it.startAndWait() }
            tracker.waitUntilAllUp(Duration.ofSeconds(60))
            tracker.stop()
        }

        private fun waitForVirtualNodeInfoReady() {
            eventually {
                val info = virtualNodeInfoReader.get(vnodeIdentity)
                assertNotNull(info)
                assertEquals(connectionIds.getValue(vnodeDb.name), info!!.cryptoDmlConnectionId)
            }
        }

        private fun assignHSMs() {
            CryptoConsts.Categories.all.forEach {
                // cluster is assigned in the crypto processor
                if (hsmRegistrationClient.findHSM(vnodeId, it) == null) {
                    hsmRegistrationClient.assignSoftHSM(vnodeId, it)
                }
            }
        }

        @JvmStatic
        fun testCategories(): Stream<Arguments> = Stream.of(
            Arguments.of(CryptoConsts.Categories.LEDGER, vnodeId),
            Arguments.of(CryptoConsts.Categories.TLS, vnodeId),
            Arguments.of(CryptoConsts.Categories.SESSION_INIT, vnodeId),
            Arguments.of(CryptoConsts.Categories.JWT_KEY, CryptoTenants.RPC_API),
            Arguments.of(CryptoConsts.Categories.TLS, CryptoTenants.P2P)
        )

        @JvmStatic
        fun testTenants(): Stream<Arguments> = Stream.of(
            Arguments.of(vnodeId),
            Arguments.of(CryptoTenants.P2P),
            Arguments.of(CryptoTenants.RPC_API)
        )
    }

    @ParameterizedTest
    @MethodSource("testCategories")
    fun `Should be able to get supported schemes`(
        category: String,
        tenantId: String
    ) {
        val supportedSchemes = opsClient.getSupportedSchemes(tenantId, category)
        assertTrue(supportedSchemes.isNotEmpty())
    }

    @ParameterizedTest
    @MethodSource("testTenants")
    fun `Should not find unknown public key by its id`(
        tenantId: String
    ) {
        val found = opsClient.lookup(
            tenantId = tenantId,
            ids = listOf(publicKeyIdFromBytes(UUID.randomUUID().toString().toByteArray()))
        )
        assertEquals(0, found.size)
    }

    @ParameterizedTest
    @MethodSource("testTenants")
    fun `Should return empty collection when lookp filter does not match`(
        tenantId: String
    ) {
        val found = opsClient.lookup(
            tenantId = tenantId,
            skip = 0,
            take = 20,
            orderBy = CryptoKeyOrderBy.NONE,
            filter = mapOf(
                CryptoConsts.SigningKeyFilters.ALIAS_FILTER to UUID.randomUUID().toString()
            )
        )
        assertEquals(0, found.size)
    }

    @ParameterizedTest
    @MethodSource("testTenants")
    fun `Should generate a new key pair using alias then find it and use for ECIES`(
        tenantId: String
    ) {
        val alias = UUID.randomUUID().toString()

        val category = CryptoConsts.Categories.SESSION_INIT

        val original = opsClient.generateKeyPair(
            tenantId = tenantId,
            category = category,
            alias = alias,
            scheme = X25519_CODE_NAME
        )

        `Should find existing public key by its id`(tenantId, alias, original, category, null)

        `Should find existing public key by its alias`(tenantId, alias, original, category)

        `Should be able to derive secret and encrypt`(tenantId, original)
    }

    @ParameterizedTest
    @MethodSource("testTenants")
    fun `Should generate a new a new fresh key pair then find it and use for ECIES`(
        tenantId: String
    ) {
        val category = CryptoConsts.Categories.SESSION_INIT

        val original = opsClient.freshKey(
            tenantId = tenantId,
            category = category,
            scheme = X25519_CODE_NAME,
            context = CryptoOpsClient.EMPTY_CONTEXT
        )

        `Should find existing public key by its id`(tenantId, null, original, category, null)

        `Should be able to derive secret and encrypt`(tenantId, original)
    }

    @ParameterizedTest
    @MethodSource("testCategories")
    fun `Should generate a new key pair using alias then find it and use for signing`(
        category: String,
        tenantId: String
    ) {
        val alias = UUID.randomUUID().toString()

        val original = opsClient.generateKeyPair(
            tenantId = tenantId,
            category = category,
            alias = alias,
            scheme = ECDSA_SECP256R1_CODE_NAME
        )

        `Should find existing public key by its id`(tenantId, alias, original, category, null)

        `Should find existing public key by its alias`(tenantId, alias, original, category)

        `Should be able to sign and verify`(tenantId, original)

        `Should be able to sign and verify by inferring signtaure spec`(tenantId, original)

        `Should be able to sign using custom signature spec`(tenantId, original)

        `Should be able to sign by flow ops and verify`(tenantId, original)

        `Should be able to sign by flow ops and verify bu inferring signature spec`(tenantId, original)
    }

    @ParameterizedTest
    @MethodSource("testTenants")
    fun `Should generate a new fresh key pair with external id then find it and use for signing`(
        tenantId: String
    ) {
        val externalId = UUID.randomUUID().toString()

        val original = opsClient.freshKey(
            tenantId = tenantId,
            category = CryptoConsts.Categories.CI,
            externalId = externalId,
            scheme = ECDSA_SECP256R1_CODE_NAME,
            context = CryptoOpsClient.EMPTY_CONTEXT
        )

        `Should find existing public key by its id`(
            tenantId,
            null,
            original,
            CryptoConsts.Categories.CI,
            externalId
        )

        `Should be able to sign and verify`(tenantId, original)

        `Should be able to sign and verify by inferring signtaure spec`(tenantId, original)

        `Should be able to sign using custom signature spec`(tenantId, original)

        `Should be able to sign by flow ops and verify`(tenantId, original)

        `Should be able to sign by flow ops and verify bu inferring signature spec`(tenantId, original)
    }

    @ParameterizedTest
    @MethodSource("testTenants")
    fun `Should generate a new fresh key pair without external id then find it and use for signing`(
        tenantId: String
    ) {
        val original = opsClient.freshKey(
            tenantId = tenantId,
            category = CryptoConsts.Categories.CI,
            scheme = ECDSA_SECP256R1_CODE_NAME,
            context = CryptoOpsClient.EMPTY_CONTEXT
        )

        `Should find existing public key by its id`(
            tenantId,
            null,
            original,
            CryptoConsts.Categories.CI,
            null
        )

        `Should be able to sign and verify`(tenantId, original)

        `Should be able to sign and verify by inferring signtaure spec`(tenantId, original)

        `Should be able to sign using custom signature spec`(tenantId, original)

        `Should be able to sign by flow ops and verify`(tenantId, original)

        `Should be able to sign by flow ops and verify bu inferring signature spec`(tenantId, original)
    }

    private fun `Should find existing public key by its id`(
        tenantId: String,
        alias: String?,
        publicKey: PublicKey,
        category: String,
        externalId: String?
    ) {
        val found = opsClient.lookup(
            tenantId = tenantId,
            ids = listOf(publicKey.publicKeyId())
        )
        assertEquals(1, found.size)
        assertEquals(publicKey.publicKeyId(), found[0].id)
        assertEquals(tenantId, found[0].tenantId)
        if (alias.isNullOrBlank()) {
            assertNull(found[0].alias)
        } else {
            assertEquals(alias, found[0].alias)
        }
        assertEquals(category, found[0].category)
        assertTrue(publicKey.encoded.contentEquals(found[0].publicKey.array()))
        assertNotNull(found[0].schemeCodeName)
        assertNotNull(found[0].masterKeyAlias)
        if (externalId.isNullOrBlank()) {
            assertNull(found[0].externalId)
        } else {
            assertEquals(externalId, found[0].externalId)
        }
        assertNull(found[0].hsmAlias)
    }

    private fun `Should find existing public key by its alias`(
        tenantId: String,
        alias: String,
        publicKey: PublicKey,
        category: String
    ) {
        val found = opsClient.lookup(
            tenantId = tenantId,
            skip = 0,
            take = 20,
            orderBy = CryptoKeyOrderBy.NONE,
            filter = mapOf(
                CryptoConsts.SigningKeyFilters.ALIAS_FILTER to alias
            )
        )
        assertEquals(1, found.size)
        assertEquals(publicKey.publicKeyId(), found[0].id)
        assertEquals(tenantId, found[0].tenantId)
        assertEquals(alias, found[0].alias)
        assertEquals(category, found[0].category)
        assertTrue(publicKey.encoded.contentEquals(found[0].publicKey.array()))
        assertNotNull(found[0].schemeCodeName)
        assertNotNull(found[0].masterKeyAlias)
        assertNull(found[0].externalId)
        assertNull(found[0].hsmAlias)
    }

    private fun `Should be able to sign and verify`(
        tenantId: String,
        publicKey: PublicKey
    ) {
        schemeMetadata.supportedSignatureSpec(schemeMetadata.findKeyScheme(publicKey)).forEach { spec ->
            val data = randomDataByteArray()
            val signature = opsClient.sign(
                tenantId = tenantId,
                publicKey = publicKey,
                signatureSpec = spec,
                data = data
            )
            assertEquals(publicKey, signature.by)
            assertTrue(signature.bytes.isNotEmpty())
            verifier.verify(
                publicKey = publicKey,
                signatureSpec = spec,
                signatureData = signature.bytes,
                clearData = data
            )
        }
    }

    private fun `Should be able to derive secret and encrypt`(tenantId: String, publicKey: PublicKey) {
        val salt = ByteArray(DigestFactory.getDigest("SHA-256").digestSize).apply {
            schemeMetadata.secureRandom.nextBytes(this)
        }
        val plainText = "Hello World!".toByteArray()
        val cipherText = ephemeralEncryptor.encrypt(
            salt = salt,
            otherPublicKey = publicKey,
            plainText = plainText,
            aad = null
        )
        val decryptedPlainTex = stableDecryptor.decrypt(
            tenantId = tenantId,
            salt = salt,
            publicKey = publicKey,
            otherPublicKey = cipherText.publicKey,
            cipherText = cipherText.cipherText,
            aad = null
        )
        assertArrayEquals(plainText, decryptedPlainTex)
    }

    private fun `Should be able to sign and verify by inferring signtaure spec`(
        tenantId: String,
        publicKey: PublicKey
    ) {
        schemeMetadata.inferableDigestNames(schemeMetadata.findKeyScheme(publicKey)).forEach { digest ->
            val data = randomDataByteArray()
            val signature = opsClient.sign(
                tenantId = tenantId,
                publicKey = publicKey,
                signatureSpec = schemeMetadata.inferSignatureSpec(publicKey, digest)!!,
                data = data
            )
            assertEquals(publicKey, signature.by)
            assertTrue(signature.bytes.isNotEmpty())
            verifier.verify(
                publicKey = publicKey,
                digest = digest,
                signatureData = signature.bytes,
                clearData = data
            )
        }
    }

    private fun `Should be able to sign using custom signature spec`(
        tenantId: String,
        publicKey: PublicKey
    ) {
        val data = randomDataByteArray()
        val signatureSpec = when (publicKey.algorithm) {
            "EC" -> SignatureSpec("SHA512withECDSA")
            "RSA" -> SignatureSpec.RSASSA_PSS_SHA256
            else -> throw IllegalArgumentException("Test supports only RSA or ECDSA")
        }
        val signature = opsClient.sign(
            tenantId = tenantId,
            publicKey = publicKey,
            signatureSpec = signatureSpec,
            data = data
        )
        assertEquals(publicKey, signature.by)
        assertTrue(signature.bytes.isNotEmpty())
        verifier.verify(
            publicKey = publicKey,
            signatureSpec = signatureSpec,
            signatureData = signature.bytes,
            clearData = data
        )
    }

    private fun `Should be able to sign by flow ops and verify`(
        tenantId: String,
        publicKey: PublicKey
    ) {
        schemeMetadata.supportedSignatureSpec(schemeMetadata.findKeyScheme(publicKey)).forEach { spec ->
            val data = randomDataByteArray()
            val key = UUID.randomUUID().toString()
            val requestId = UUID.randomUUID().toString()
            val event = transformer.createSign(
                requestId = requestId,
                tenantId = tenantId,
                publicKey = publicKey,
                signatureSpec = spec,
                data = data,
                flowExternalEventContext = ExternalEventContext(requestId, key)
            )
            logger.info(
                "Publishing: createSign({}, {}, {}), request id: $requestId, flow id: $key",
                tenantId,
                publicKey.publicKeyId(),
                spec
            )
            publisher.publish(
                listOf(
                    Record(
                        topic = FLOW_OPS_MESSAGE_TOPIC,
                        key = key,
                        value = event
                    )
                )
            ).forEach { it.get() }
            logger.info("Waiting for response for createSign")
            val response = flowOpsResponses.waitForResponse(key)
            val signature = transformer.transform(response) as DigitalSignature.WithKey
            assertEquals(publicKey, signature.by)
            assertTrue(signature.bytes.isNotEmpty())
            verifier.verify(
                publicKey = publicKey,
                signatureSpec = spec,
                signatureData = signature.bytes,
                clearData = data
            )
        }
    }

    private fun `Should be able to sign by flow ops and verify bu inferring signature spec`(
        tenantId: String,
        publicKey: PublicKey
    ) {
        schemeMetadata.inferableDigestNames(schemeMetadata.findKeyScheme(publicKey)).forEach { digest ->
            val data = randomDataByteArray()
            val key = UUID.randomUUID().toString()
            val spec = schemeMetadata.inferSignatureSpec(publicKey, digest)!!
            val requestId = UUID.randomUUID().toString()
            val event = transformer.createSign(
                requestId = requestId,
                tenantId = tenantId,
                publicKey = publicKey,
                signatureSpec = spec,
                data = data,
                flowExternalEventContext = ExternalEventContext(requestId, key)
            )
            logger.info(
                "Publishing: createSign({}, {}, {})",
                tenantId,
                publicKey.publicKeyId(),
                spec
            )
            publisher.publish(
                listOf(
                    Record(
                        topic = FLOW_OPS_MESSAGE_TOPIC,
                        key = key,
                        value = event
                    )
                )
            ).forEach { it.get() }
            logger.info("Waiting for response for createSign")
            val response = flowOpsResponses.waitForResponse(key)
            val signature = transformer.transform(response) as DigitalSignature.WithKey
            assertEquals(publicKey, signature.by)
            assertTrue(signature.bytes.isNotEmpty())
            verifier.verify(
                publicKey = publicKey,
                digest = digest,
                signatureData = signature.bytes,
                clearData = data
            )
        }
    }
}
