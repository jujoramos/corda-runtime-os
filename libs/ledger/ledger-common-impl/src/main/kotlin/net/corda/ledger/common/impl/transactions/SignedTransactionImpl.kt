package net.corda.ledger.common.impl.transactions

import net.corda.v5.application.crypto.DigitalSignatureAndMetadata
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.base.util.uncheckedCast
import net.corda.v5.ledger.common.transactions.LedgerTransaction
import net.corda.v5.ledger.common.transactions.SignedTransaction
import net.corda.v5.ledger.common.transactions.WireTransaction
import java.security.PublicKey

class SignedTransactionImpl(
    override val wireTransaction: WireTransaction,
    override val sigs: List<DigitalSignatureAndMetadata>
    ) :SignedTransaction
{

    init {
        require(sigs.isNotEmpty()) {
            "Tried to instantiate a ${SignedTransactionImpl::class.java.simpleName} without any signatures "
        }
    }

    /** Specifies all the public keys that require signatures for the transaction to be valid. */
    override val requiredSigningKeys: List<PublicKey>
        get() = ledgerTransaction.requiredSigningKeys

    // TODO(WIP)
    override fun <T: LedgerTransaction>toLedgerTransaction(serializer: SerializationService): T {
        val clazz = Class.forName(this.wireTransaction.getWrappedLedgerTransactionClassName(serializer))
        return uncheckedCast<Any, T>(clazz.constructors.first().newInstance(this.wireTransaction, serializer))
    }

    /** Returns the same transaction but with an additional (unchecked) signature. */
    override fun withAdditionalSignature(sig: DigitalSignatureAndMetadata): SignedTransaction =
        SignedTransactionImpl(wireTransaction, sigs + sig)

    /** Returns the same transaction but with an additional (unchecked) signatures. */
    override fun withAdditionalSignatures(sigList: Iterable<DigitalSignatureAndMetadata>): SignedTransaction =
        SignedTransactionImpl(wireTransaction, sigs + sigList)
}