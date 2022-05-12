package net.corda.httprpc.server.impl.factory

import net.corda.httprpc.security.read.RPCSecurityManager
import net.corda.httprpc.server.impl.HttpRpcServerImpl
import net.corda.httprpc.server.HttpRpcServer
import net.corda.httprpc.server.config.models.HttpRpcSettings
import net.corda.httprpc.server.factory.HttpRpcServerFactory
import net.corda.httprpc.PluggableRPCOps
import net.corda.httprpc.RpcOps
import net.corda.httprpc.jwt.HttpRpcTokenProcessor
import org.osgi.service.component.annotations.Component
import java.nio.file.Path

@Component(immediate = true, service = [HttpRpcServerFactory::class])
@Suppress("Unused")
class HttpRpcServerFactoryImpl : HttpRpcServerFactory {

    override fun createHttpRpcServer(
        rpcOpsImpls: List<PluggableRPCOps<out RpcOps>>,
        rpcSecurityManager: RPCSecurityManager,
        httpRpcSettings: HttpRpcSettings,
        multiPartDir: Path,
        jwtProcessor: HttpRpcTokenProcessor
    ): HttpRpcServer {

        return HttpRpcServerImpl(rpcOpsImpls, rpcSecurityManager, httpRpcSettings, multiPartDir, jwtProcessor, devMode = false)
    }
}