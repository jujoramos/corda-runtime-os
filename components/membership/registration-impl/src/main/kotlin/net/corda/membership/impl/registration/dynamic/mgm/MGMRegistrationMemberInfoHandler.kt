package net.corda.membership.impl.registration.dynamic.mgm

import net.corda.crypto.cipher.suite.KeyEncodingService
import net.corda.crypto.cipher.suite.calculateHash
import net.corda.crypto.client.CryptoOpsClient
import net.corda.crypto.core.CryptoConsts.Categories.PRE_AUTH
import net.corda.crypto.core.CryptoConsts.Categories.SESSION_INIT
import net.corda.crypto.core.ShortHash
import net.corda.crypto.core.ShortHashException
import net.corda.libs.platform.PlatformInfoProvider
import net.corda.membership.lib.MemberInfoExtension.Companion.CREATION_TIME
import net.corda.membership.lib.MemberInfoExtension.Companion.ECDH_KEY
import net.corda.membership.lib.MemberInfoExtension.Companion.GROUP_ID
import net.corda.membership.lib.MemberInfoExtension.Companion.IS_MGM
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_CPI_NAME
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_CPI_SIGNER_HASH
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_CPI_VERSION
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_STATUS_ACTIVE
import net.corda.membership.lib.MemberInfoExtension.Companion.MODIFIED_TIME
import net.corda.membership.lib.MemberInfoExtension.Companion.PARTY_NAME
import net.corda.membership.lib.MemberInfoExtension.Companion.PARTY_SESSION_KEYS
import net.corda.membership.lib.MemberInfoExtension.Companion.PARTY_SESSION_KEYS_PEM
import net.corda.membership.lib.MemberInfoExtension.Companion.PLATFORM_VERSION
import net.corda.membership.lib.MemberInfoExtension.Companion.SERIAL
import net.corda.membership.lib.MemberInfoExtension.Companion.SESSION_KEYS
import net.corda.membership.lib.MemberInfoExtension.Companion.SESSION_KEYS_HASH
import net.corda.membership.lib.MemberInfoExtension.Companion.SOFTWARE_VERSION
import net.corda.membership.lib.MemberInfoExtension.Companion.STATUS
import net.corda.membership.lib.MemberInfoFactory
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.membership.persistence.client.MembershipPersistenceResult
import net.corda.utilities.time.Clock
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.membership.MemberInfo
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import java.security.PublicKey

@Suppress("LongParameterList")
internal class MGMRegistrationMemberInfoHandler(
    private val clock: Clock,
    private val cryptoOpsClient: CryptoOpsClient,
    private val keyEncodingService: KeyEncodingService,
    private val memberInfoFactory: MemberInfoFactory,
    private val membershipPersistenceClient: MembershipPersistenceClient,
    private val platformInfoProvider: PlatformInfoProvider,
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService
) {

    private companion object {
        const val SERIAL_CONST = "1"
        val keyIdList = listOf(SESSION_KEYS, ECDH_KEY_ID)
        val sessionKeyRegex = String.format("$PARTY_SESSION_KEYS.id", "[0-9]+").toRegex()
    }

    @Throws(MGMRegistrationMemberInfoHandlingException::class)
    fun buildAndPersistMgmMemberInfo(
        holdingIdentity: HoldingIdentity,
        context: Map<String, String>
    ): MemberInfo {
        return buildMgmInfo(holdingIdentity, context).also {
            persistMemberInfo(holdingIdentity, it)
        }
    }

    @Suppress("ThrowsCount")
    private fun getKeyFromId(keyId: String, tenantId: String, expectedCategory: String): PublicKey {
        val parsedKeyId =
            try {
                ShortHash.parse(keyId)
            } catch (e: ShortHashException) {
                throw IllegalArgumentException(e)
            }
        return cryptoOpsClient.lookupKeysByIds(
            tenantId,
            listOf(parsedKeyId)
        ).firstOrNull()?.let {
            if (it.category != expectedCategory) {
                throw MGMRegistrationContextValidationException(
                    "Wrong key category. Key ID: $keyId category is ${it.category}. please use key from the $expectedCategory category.",
                    null
                )
            }
            try {
                keyEncodingService.decodePublicKey(it.publicKey.array())
            } catch (ex: RuntimeException) {
                throw MGMRegistrationMemberInfoHandlingException(
                    "Could not decode public key for tenant ID: " +
                            "$tenantId under ID: $keyId.", ex
                )
            }
        } ?: throw MGMRegistrationMemberInfoHandlingException(
            "No key found for tenant: $tenantId under ID: $keyId."
        )
    }

    private fun PublicKey.toPem(): String = keyEncodingService.encodeAsString(this)

    private fun persistMemberInfo(holdingIdentity: HoldingIdentity, mgmInfo: MemberInfo) {
        val persistenceResult = membershipPersistenceClient.persistMemberInfo(holdingIdentity, listOf(mgmInfo))
        if (persistenceResult is MembershipPersistenceResult.Failure) {
            throw MGMRegistrationMemberInfoHandlingException(
                "Registration failed, persistence error. Reason: ${persistenceResult.errorMsg}"
            )
        }
    }

    private fun buildMgmInfo(
        holdingIdentity: HoldingIdentity,
        context: Map<String, String>
    ): MemberInfo {
        val cpi = virtualNodeInfoReadService.get(holdingIdentity)?.cpiIdentifier
            ?: throw MGMRegistrationMemberInfoHandlingException(
                "Could not find virtual node info for member ${holdingIdentity.shortHash}"
            )
        val ecdhKey = getKeyFromId(context[ECDH_KEY_ID]!!, holdingIdentity.shortHash.value, PRE_AUTH)
        if (ecdhKey.algorithm != "EC") {
            throw MGMRegistrationContextValidationException("ECDH key must be created with an EC schema.", null)
        }
        val now = clock.instant().toString()
        val optionalContext = mapOf(MEMBER_CPI_SIGNER_HASH to cpi.signerSummaryHash.toString())
        val sessionKeys = context.filterKeys { key ->
            sessionKeyRegex.matches(key)
        }.values
            .map {
                getKeyFromId(it, holdingIdentity.shortHash.value, SESSION_INIT)
            }.flatMapIndexed { index, sessionKey ->
                listOf(
                    String.format(PARTY_SESSION_KEYS_PEM, index) to sessionKey.toPem(),
                    String.format(SESSION_KEYS_HASH, index) to sessionKey.calculateHash().value,
                )
            }
        val memberContext = context.filterKeys { key ->
            !keyIdList.any { keyPrefix ->
                key.startsWith(keyPrefix)
            }
        }.filterKeys {
            !it.startsWith(GROUP_POLICY_PREFIX_WITH_DOT)
        } + mapOf(
            GROUP_ID to holdingIdentity.groupId,
            PARTY_NAME to holdingIdentity.x500Name.toString(),
            ECDH_KEY to ecdhKey.toPem(),
            PLATFORM_VERSION to platformInfoProvider.activePlatformVersion.toString(),
            SOFTWARE_VERSION to platformInfoProvider.localWorkerSoftwareVersion,
            MEMBER_CPI_NAME to cpi.name,
            MEMBER_CPI_VERSION to cpi.version,
        ) + optionalContext + sessionKeys
        return memberInfoFactory.create(
            memberContext = memberContext.toSortedMap(),
            mgmContext = sortedMapOf(
                CREATION_TIME to now,
                MODIFIED_TIME to now,
                STATUS to MEMBER_STATUS_ACTIVE,
                IS_MGM to "true",
                SERIAL to SERIAL_CONST,
            )
        )
    }
}

internal class MGMRegistrationMemberInfoHandlingException(
    val reason: String,
    ex: Throwable? = null
) : CordaRuntimeException(reason, ex)