package net.corda.libs.virtualnode.maintenance.endpoints.v1

import net.corda.httprpc.HttpFileUpload
import net.corda.httprpc.RpcOps
import net.corda.httprpc.annotations.HttpRpcPOST
import net.corda.httprpc.annotations.HttpRpcPUT
import net.corda.httprpc.annotations.HttpRpcPathParameter
import net.corda.httprpc.annotations.HttpRpcResource
import net.corda.libs.cpiupload.endpoints.v1.CpiUploadRPCOps
import net.corda.libs.virtualnode.maintenance.endpoints.v1.types.ChangeVirtualNodeStateResponse

/**
 * Maintenance RPC operations for virtual node management.
 *
 * Some of them could be highly disruptive, so great care should be taken when using them.
 */
@HttpRpcResource(
    name = "Virtual Node Maintenance API",
    description = "Virtual node maintenance endpoints.",
    path = "maintenance/virtualnode"
)
interface VirtualNodeMaintenanceRPCOps : RpcOps {

    /**
     * HTTP POST to force upload of a CPI.
     *
     * Even if CPI with the same metadata has already been previously uploaded, this endpoint will overwrite earlier
     * stored CPI record.
     * Furthermore, any sandboxes running an overwritten version of CPI will be purged and optionally vault data for
     * the affected Virtual Nodes wiped out.
     */
    @HttpRpcPOST(
        path = "forceCpiUpload",
        title = "Upload a CPI",
        description = "Uploads a CPI",
        responseDescription = "The request Id calculated for a CPI upload request"
    )
    fun forceCpiUpload(upload: HttpFileUpload): CpiUploadRPCOps.CpiUploadResponse

    /**
     * Updates a virtual nodes state.
     *
     * @throws `VirtualNodeRPCOpsServiceException` If the virtual node update request could not be published.
     * @throws `HttpApiException` If the request returns an exceptional response.
     */
    @HttpRpcPUT(
        path = "{virtualNodeShortId}/state/{newState}",
        title = "Update virtual node state",
        description = "Updates the state of a new virtual node.",
        responseDescription = "The details of the updated virtual node."
    )
    fun updateVirtualNodeState(
        @HttpRpcPathParameter(description = "Short ID of the virtual node instance to update")
        virtualNodeShortId: String,
        @HttpRpcPathParameter(description = "State to transition virtual node instance into")
        newState: String
    ): ChangeVirtualNodeStateResponse
}
