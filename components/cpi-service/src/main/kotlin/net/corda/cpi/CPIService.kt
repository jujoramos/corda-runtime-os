package net.corda.cpi

import net.corda.lifecycle.Lifecycle
import net.corda.packaging.CordappManifest
import net.corda.v5.crypto.SecureHash
import java.io.InputStream
import java.security.cert.Certificate
import java.util.NavigableSet
import java.util.concurrent.CompletableFuture
import javax.security.auth.x500.X500Principal

data class Signer(val subject : X500Principal, val rootCert : Certificate) : Comparable<Signer> {
    override fun compareTo(other: Signer): Int {
        TODO("Not yet implemented")
    }
}

interface CPI {

    interface Identity : Comparable<Identity> {
        val name : X500Principal
        val groupId : String
    }
    
    interface Identifier : Comparable<Identifier> {
        val name : String
        val version : String
        val signerSummaryHash : SecureHash
        val identity : Identity?
    }

    interface Metadata {
        val id : Identifier
        val cpks : List<CPK.Metadata>
        val networkPolicy : String?
    }

    val data : Metadata
    val cpks : List<CPK>

    fun getCpk(id : CPK.Identifier) : CPK
}

interface CPK {

    interface Identifier : Comparable<Identifier> {
        val name : String
        val version : String
        val signerSummaryHash : SecureHash
    }

    enum class Type constructor(private val text : String?) : Comparable<Type> {
        CORDA_API("corda-api"), UNKNOWN(null);
    }

    interface Metadata {
        val id : Identifier
        val mainBundle : String
        val libraries : List<String>
        val dependencies : NavigableSet<Identifier>
        val cordappManifest : CordappManifest
        val type : Type
        val hash: SecureHash
    }

    val data : Metadata
    fun getResourceAsStream(resourceName : String) : InputStream?
}

interface CPIServiceHandler {
    fun onUpdatedCPIList(cpiIdentifier: List<CPI.Identifier>)
}

interface CPIService : Lifecycle {

    fun get(id: CPI.Identifier): CompletableFuture<CPI>?

    fun get(id : CPK.Identifier): CompletableFuture<CPK>?

    fun getCPKByHash(hash : SecureHash): CompletableFuture<CPK>?

    fun listCPK() : List<CPK.Metadata>

    fun listCPI() : List<CPI.Metadata>

    fun registerForUpdates(cpiServiceHandler: CPIServiceHandler) : AutoCloseable
}