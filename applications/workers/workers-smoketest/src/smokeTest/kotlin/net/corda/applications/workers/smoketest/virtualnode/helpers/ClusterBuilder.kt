package net.corda.applications.workers.smoketest.virtualnode.helpers

import java.net.URI
import java.nio.file.Paths

/**
 *  All functions return a [SimpleResponse] if not explicitly declared.
 *
 *  The caller needs to marshall the response body to json, and then query
 *  the json for the expected results.
 */
class ClusterBuilder {
    private var client: HttpsClient? = null

    fun endpoint(uri: URI, username: String, password: String) {
        client = UnirestHttpsClient(uri, username, password)
    }

    /** POST, but most useful for running flows */
    fun post(cmd: String, body: String) = client!!.post(cmd, body)

    fun put(cmd: String, body: String) = client!!.put(cmd, body)

    fun get(cmd: String) = client!!.get(cmd)

    private fun uploadCpiResource(cmd: String, resourceName: String, groupId: String): SimpleResponse {
        val fileName = Paths.get(resourceName).fileName.toString()
        return CpiLoader.get(resourceName, groupId).use {
            client!!.postMultiPart(cmd, emptyMap(), mapOf("upload" to HttpsClientFileUpload(it, fileName)))
        }
    }

    private fun uploadUnmodifiedResource(cmd: String, resourceName: String): SimpleResponse {
        val fileName = Paths.get(resourceName).fileName.toString()
        return CpiLoader.getRawResource(resourceName).use {
            client!!.postMultiPart(cmd, emptyMap(), mapOf("upload" to HttpsClientFileUpload(it, fileName)))
        }
    }

    /** Assumes the resource *is* a CPB */
    fun cpbUpload(resourceName: String) = uploadUnmodifiedResource("/api/v1/cpi/", resourceName)

    /** Assumes the resource is a CPB and converts it to CPI by adding a group policy file */
    fun cpiUpload(resourceName: String, groupId: String) = uploadCpiResource("/api/v1/cpi/", resourceName, groupId)

    /** Assumes the resource is a CPB and converts it to CPI by adding a group policy file */
    fun forceCpiUpload(resourceName: String, groupId: String) =
        uploadCpiResource("/api/v1/maintenance/virtualnode/forcecpiupload/", resourceName, groupId)

    /** Return the status for the given request id */
    fun cpiStatus(id: String) = client!!.get("/api/v1/cpi/status/$id")

    /** List all CPIs in the system */
    fun cpiList() = client!!.get("/api/v1/cpi")

    private fun vNodeBody(cpiHash: String, x500Name: String) =
        """{ "request": { "cpiFileChecksum" : "$cpiHash", "x500Name" : "$x500Name"} }"""

    /** Create a virtual node */
    fun vNodeCreate(cpiHash: String, x500Name: String) =
        client!!.post("/api/v1/virtualnode", vNodeBody(cpiHash, x500Name))

    /** List all virtual nodes */
    fun vNodeList() = client!!.get("/api/v1/virtualnode")

    fun addSoftHsmToVNode(holdingIdHash: String, category: String) =
        client!!.post("/api/v1/$holdingIdHash/hsm/soft?category=$category", body = "")

    fun createKey(holdingIdHash: String, alias: String, category: String, scheme: String) =
        client!!.post(
            "/api/v1/keys/$holdingIdHash",
            body = """{
                    "alias": "$alias",
                    "hsmCategory": "$category",
                    "scheme": "$scheme"
                }""".trimIndent()
        )

    fun getKey(holdingIdHash: String, keyId: String) =
        client!!.get("/api/v1/keys/$holdingIdHash/$keyId")

    /** Get status of a flow */
    fun flowStatus(holdingIdHash: String, clientRequestId: String) =
        client!!.get("/api/v1/flow/$holdingIdHash/$clientRequestId")

    /** Get status of multiple flows */
    fun multipleFlowStatus(holdingIdHash: String) =
        client!!.get("/api/v1/flow/$holdingIdHash")

    /** Get status of multiple flows */
    fun runnableFlowClasses(holdingIdHash: String) =
        client!!.get("/api/v1/flowclass/$holdingIdHash")

    /** Start a flow */
    fun flowStart(
        holdingIdHash: String,
        clientRequestId: String,
        flowClassName: String,
        requestData: String
    ): SimpleResponse {
        return client!!.post("/api/v1/flow/$holdingIdHash", flowStartBody(clientRequestId, flowClassName, requestData))
    }

    private fun flowStartBody(clientRequestId: String, flowClassName: String, requestData: String) =
        """{ "httpStartFlow" : { "clientRequestId" : "$clientRequestId", "flowClassName" : "$flowClassName", "requestData" : 
            |"$requestData"} }""".trimMargin()

}

fun <T> cluster(initialize: ClusterBuilder.() -> T):T = ClusterBuilder().let(initialize)
