package net.corda.membership.impl.grouppolicy

import net.corda.cpiinfo.read.CpiInfoReadService
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.lifecycle.createCoordinator
import net.corda.membership.grouppolicy.GroupPolicyProvider
import net.corda.membership.lib.exceptions.BadGroupPolicyException
import net.corda.membership.lib.grouppolicy.GroupPolicy
import net.corda.membership.lib.grouppolicy.GroupPolicyParser
import net.corda.membership.persistence.client.MembershipQueryClient
import net.corda.membership.persistence.client.MembershipQueryResult
import net.corda.v5.base.types.LayeredPropertyMap
import net.corda.v5.base.util.contextLogger
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.VirtualNodeInfo
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@Component(service = [GroupPolicyProvider::class])
class GroupPolicyProviderImpl @Activate constructor(
    @Reference(service = VirtualNodeInfoReadService::class)
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService,
    @Reference(service = CpiInfoReadService::class)
    private val cpiInfoReader: CpiInfoReadService,
    @Reference(service = LifecycleCoordinatorFactory::class)
    private val lifecycleCoordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = GroupPolicyParser::class)
    private val groupPolicyParser: GroupPolicyParser,
    @Reference(service = MembershipQueryClient::class)
    private val membershipQueryClient: MembershipQueryClient,
) : GroupPolicyProvider {

    /**
     * Private interface used for implementation swapping in response to lifecycle events.
     */
    private interface InnerGroupPolicyProvider : AutoCloseable {
        fun getGroupPolicy(holdingIdentity: HoldingIdentity): GroupPolicy?
    }

    companion object {
        val logger = contextLogger()
    }

    private var registrationHandle: AutoCloseable? = null

    private val coordinator = lifecycleCoordinatorFactory
        .createCoordinator<GroupPolicyProvider>(::handleEvent)

    private var impl: InnerGroupPolicyProvider = InactiveImpl

    override fun getGroupPolicy(holdingIdentity: HoldingIdentity) = impl.getGroupPolicy(holdingIdentity)
    override fun registerListener(callback: (HoldingIdentity, GroupPolicy) -> Unit) {
        listeners.add(callback)
    }

    override fun start() = coordinator.start()

    override fun stop() = coordinator.stop()

    override val isRunning get() = coordinator.isRunning

    private val listeners: MutableList<(HoldingIdentity, GroupPolicy) -> Unit> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Handle lifecycle events.
     */
    private fun handleEvent(event: LifecycleEvent, coordinator: LifecycleCoordinator) {
        logger.info("Group policy provider received event $event.")
        when (event) {
            is StartEvent -> {
                logger.info("Group policy provider starting.")
                registrationHandle?.close()
                registrationHandle = coordinator.followStatusChangesByName(
                    setOf(
                        LifecycleCoordinatorName.forComponent<VirtualNodeInfoReadService>(),
                        LifecycleCoordinatorName.forComponent<CpiInfoReadService>(),
                        LifecycleCoordinatorName.forComponent<MembershipQueryClient>()
                    )
                )
            }
            is StopEvent -> {
                logger.info("Group policy provider stopping.")
                deactivate("Stopping component.")
                registrationHandle?.close()
            }
            is RegistrationStatusChangeEvent -> {
                logger.info("Group policy provider handling registration change. Event status: ${event.status}")
                when (event.status) {
                    LifecycleStatus.UP -> {
                        swapImpl(ActiveImpl())
                        coordinator.updateStatus(LifecycleStatus.UP, "All dependencies are UP.")
                    }
                    else -> {
                        deactivate("All dependencies are not UP.")
                    }
                }
            }
        }
    }

    private fun deactivate(reason: String) {
        coordinator.updateStatus(LifecycleStatus.DOWN, reason)
        swapImpl(InactiveImpl)
    }

    private fun swapImpl(newImpl: InnerGroupPolicyProvider) {
        val current = impl
        impl = newImpl
        current.close()
    }

    private object InactiveImpl : InnerGroupPolicyProvider {
        override fun getGroupPolicy(holdingIdentity: HoldingIdentity): GroupPolicy =
            throw IllegalStateException("Service is in incorrect state for accessing group policies.")

        override fun close() = Unit
    }

    private inner class ActiveImpl : InnerGroupPolicyProvider {
        private val groupPolicies: MutableMap<HoldingIdentity, GroupPolicy?> = ConcurrentHashMap()

        private var virtualNodeInfoCallbackHandle: AutoCloseable = startVirtualNodeHandle()

        override fun getGroupPolicy(
            holdingIdentity: HoldingIdentity
        ) = try {
            groupPolicies.computeIfAbsent(holdingIdentity) { parseGroupPolicy(it) }
        } catch (e: BadGroupPolicyException) {
            logger.error("Could not parse group policy file for holding identity [$holdingIdentity].", e)
            null
        } catch (e: Throwable) {
            logger.error("Unexpected exception occurred when retrieving group policy file for " +
                    "holding identity [$holdingIdentity].", e)
            null
        }

        override fun close() {
            virtualNodeInfoCallbackHandle.close()
            groupPolicies.clear()
        }

        /**
         * Parse the group policy string to a [GroupPolicy] object.
         *
         * [VirtualNodeInfoReadService] is used to get the [VirtualNodeInfo], unless provided as a parameter. It may be
         * the case in a virtual node info callback where we are given the changed virtual node info.
         *
         * [CpiInfoReadService] is used to get the CPI metadata containing the group policy for the CPI installed on
         * the virtual node.
         *
         * The group policy is cached to simplify lookups later.
         *
         * @param holdingIdentity The holding identity of the member retrieving the group policy.
         * @param virtualNodeInfo if the VirtualNodeInfo is known, it can be passed in instead of getting this from the
         *  virtual node info reader.
         */
        private fun parseGroupPolicy(
            holdingIdentity: HoldingIdentity,
            virtualNodeInfo: VirtualNodeInfo? = null,
        ): GroupPolicy? {
            val vNodeInfo = virtualNodeInfo ?: virtualNodeInfoReadService.get(holdingIdentity)
            if (vNodeInfo == null) {
                logger.warn("Could not get virtual node info for holding identity [${holdingIdentity}]")
            }
            val metadata = vNodeInfo?.cpiIdentifier?.let { cpiInfoReader.get(it) }
            if (metadata == null) {
                logger.warn(
                    "Could not get CPI metadata for holding identity [${holdingIdentity}] and CPI with " +
                            "identifier [${vNodeInfo?.cpiIdentifier.toString()}]"
                )
            }
            fun persistedPropertyQuery(): LayeredPropertyMap? = try {
                membershipQueryClient.queryGroupPolicy(holdingIdentity).getOrThrow()
            } catch (e: MembershipQueryResult.QueryException) {
                logger.warn("Failed to retrieve persisted group policy properties.", e)
                null
            }
            return try {
                groupPolicyParser.parse(
                    holdingIdentity,
                    metadata?.groupPolicy,
                    ::persistedPropertyQuery
                )
            } catch (e: BadGroupPolicyException) {
                logger.warn("Failed to parse group policy. Returning null.", e)
                null
            }
        }

        /**
         * Register callback so that if a holding identity modifies their virtual node information, the
         * group policy for that holding identity will be parsed in case the virtual node change affected the
         * group policy file.
         */
        private fun startVirtualNodeHandle(): AutoCloseable =
            virtualNodeInfoReadService.registerCallback { changed, snapshot ->
                logger.info("Processing new snapshot after change in virtual node information.")
                changed.filter { snapshot[it] != null }.forEach {
                    groupPolicies.compute(it) { _, _ ->
                        try {
                            parseGroupPolicy(it, virtualNodeInfo = snapshot[it])
                        } catch (e: Exception) {
                            logger.error(
                                "Failure to parse group policy after change in virtual node info. " +
                                        "Check the format of the group policy in use for virtual node with ID [${it.shortHash}]. " +
                                        "Caught exception: ", e
                            )
                            logger.warn(
                                "Removing cached group policy due to problem when parsing update so it will be " +
                                        "repopulated on next read."
                            )
                            null
                        }
                    }
                    synchronized(listeners) {
                        listeners.forEach { callback -> callback(it, groupPolicies[it]!!) }
                    }
                }
            }
    }
}
