package net.corda.p2p.deployment.pods

class Gateway(
    kafkaServers: String,
    details: P2PDeploymentDetails,
) : P2pPod(kafkaServers, details, details.gatewayCount) {
    companion object {
        const val IMAGE_NAME = "p2p-gateway-worker"
        fun gateways(
            kafkaServers: String,
            details: P2PDeploymentDetails,
        ): Collection<Yamlable> {
            val gateways = listOf(
                Gateway(kafkaServers, details)
            )
            val balancer = when (details.lbType) {
                LbType.K8S -> K8sLoadBalancer(
                    IMAGE_NAME,
                    listOf(Port.Gateway),
                )
                LbType.NGINX -> if (details.nginxCount <= 1) {
                    NginxLoadBalancer(
                        gateways.map { it.app },
                        true,
                    )
                } else {
                    HeadlessNginxLoadBalancer(
                        details.nginxCount,
                        gateways.map { it.app },
                    )
                }

                LbType.HEADLESS -> HeadlessService(
                    IMAGE_NAME
                )
            }
            return gateways + balancer
        }
    }
    override val imageName = IMAGE_NAME

    override val readyLog = ".*Gateway-1.* - Starting child.*".toRegex()

    override val otherPorts = when (details.lbType) {
        // In K8S load balancer the load balancer service will listen to the Gateway port, so no need to create a service.
        LbType.K8S -> emptyList()
        LbType.NGINX -> listOf(
            Port.Gateway
        )
        // In HEADLESS load balancer the headless service will act as the load balancer, no need to create an extra service per gateway.
        LbType.HEADLESS -> emptyList()
    }
}
