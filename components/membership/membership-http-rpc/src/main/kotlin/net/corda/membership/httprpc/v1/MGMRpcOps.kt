package net.corda.membership.httprpc.v1

import net.corda.httprpc.RpcOps
import net.corda.httprpc.annotations.HttpRpcDELETE
import net.corda.httprpc.annotations.HttpRpcGET
import net.corda.httprpc.annotations.HttpRpcPUT
import net.corda.httprpc.annotations.HttpRpcPathParameter
import net.corda.httprpc.annotations.HttpRpcResource

/**
 * The MGM API consists of a number of endpoints used to manage membership groups. A membership group is a logical
 * grouping of a number of Corda Identities to communicate and transact with one another with a specific set of CorDapps.
 * The API allows you to generate the group policy for a membership group, required for new members to join the group.
 */
@HttpRpcResource(
    name = "MGM API",
    description = "The MGM API consists of a number of endpoints used to manage membership groups. A membership group" +
            " is a logical grouping of a number of Corda Identities to communicate and transact with one another with" +
            " a specific set of CorDapps. The API allows you to generate the group policy for a membership group," +
            " required for new members to join the group.",
    path = "mgm"
)
interface MGMRpcOps : RpcOps {
    /**
     * The [generateGroupPolicy] method enables you to retrieve the group policy from the MGM represented by
     * [holdingIdentityShortHash], required for new members to join the membership group.
     *
     * Example usage:
     * ```
     * mgmOps.generateGroupPolicy(holdingIdentityShortHash = "58B6030FABDD")
     * ```
     *
     * @param holdingIdentityShortHash The holding identity ID of the MGM of the membership group to be joined.
     *
     * @return The group policy generated by the MGM in JSON [String] format.
     */
    @HttpRpcGET(
        path = "{holdingIdentityShortHash}/info",
        description = "This method retrieves the group policy from the MGM required to join the membership group.",
        responseDescription = "The group policy from the MGM required to join the membership group as a string " +
                "in JSON format"
    )
    fun generateGroupPolicy(
        @HttpRpcPathParameter(description = "The holding identity ID of the MGM of the membership group to be joined")
        holdingIdentityShortHash: String
    ): String

    /**
     * Adds client certificate subject to the mutual TLS allowed client certificates.
     *
     * @param holdingIdentityShortHash The holding identity ID of the MGM.
     * @param subject The certificate subject.
     */
    @HttpRpcPUT(
        path = "{holdingIdentityShortHash}/mutual-tls/allowed-client-certificate-subjects/{subject}",
        description = "This method allows a client certificate with a " +
            "given subject to be used in mutual TLS connections.",
    )
    fun mutualTlsAllowClientCertificate(
        @HttpRpcPathParameter(description = "The holding identity ID of the MGM.")
        holdingIdentityShortHash: String,
        @HttpRpcPathParameter(description = "The certificate subject.")
        subject: String,
    )

    /**
     * Remove client certificate subject from the mutual TLS group allowed client certificates.
     *
     * @param holdingIdentityShortHash The holding identity ID of the MGM.
     * @param subject The certificate subject.
     */
    @HttpRpcDELETE(
        path = "{holdingIdentityShortHash}/mutual-tls/allowed-client-certificate-subjects/{subject}",
        description = "This method disallows a client certificate with a " +
                "given subject to be used in mutual TLS connections.",
    )
    fun mutualTlsDisallowClientCertificate(
        @HttpRpcPathParameter(description = "The holding identity ID of the MGM.")
        holdingIdentityShortHash: String,
        @HttpRpcPathParameter(description = "The certificate subject.")
        subject: String,
    )

    /**
     * List the allowed client certificate subjects for mutual TLS.
     *
     * @param holdingIdentityShortHash The holding identity ID of the MGM.
     * @return List of the allowed client certificate subjects.
     */
    @HttpRpcGET(
        path = "{holdingIdentityShortHash}/mutual-tls/allowed-client-certificate-subjects",
        description = "This method list the allowed  client certificates subjects " +
                "to be used in mutual TLS connections.",
        responseDescription = "List of the allowed client certificate subjects",
    )
    fun mutualTlsListClientCertificate(
        @HttpRpcPathParameter(description = "The holding identity ID of the MGM.")
        holdingIdentityShortHash: String,
    ): Collection<String>
}