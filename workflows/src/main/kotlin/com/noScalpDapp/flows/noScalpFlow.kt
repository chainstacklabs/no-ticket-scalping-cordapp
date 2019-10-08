package com.noScalpDapp.flows

import co.paralleluniverse.fibers.Suspendable
import com.noScalpDapp.contracts.NOSCALP_CONTRACT_ID
import net.corda.core.contracts.StateAndContract
import com.noScalpDapp.flows.noScalpFlow.Acceptor
import com.noScalpDapp.flows.noScalpFlow.Initiator
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import com.noScalpDapp.contracts.noScalpContract
import com.noScalpDapp.states.noScalpState

/**
 * This flow registers an on-ledger fact between two distributors (the [Initiator] and the [Acceptor]) as they automatically
 * come to an agreement about the distribution.
 *
 * The [Initiator] is the [Distributor] that starts the Flow.
 *
 * The [Acceptor] is the [toDistributor].
 *
 * The [Acceptor] always accepts the ticket distribution.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object noScalpFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val ticketQuantity: Int,
                    private val eventName: String,
                    private val toDistributor: Party) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on the distribution parameters.")
            object VERIFYING_TRANSACTION : Step("Verifying the distribution constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the other distributor's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording the distribution transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION

            // Generate an unsigned transaction.
            val txBuilder = TransactionBuilder(notary)

            // Create transaction components.
            val outputState = noScalpState(ticketQuantity, eventName, ourIdentity, toDistributor)
            val outputContract = StateAndContract(outputState, NOSCALP_CONTRACT_ID)
            val cmd = Command(noScalpContract.Create(), listOf(ourIdentity.owningKey, toDistributor.owningKey))
            txBuilder.withItems(outputContract, cmd)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION

            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION

            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS

            // Send the state to the counterparty [toDistributor], and receive it back with their signature.
            val otherPartySession = initiateFlow(toDistributor)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION

            // Notarize and record the transaction in both distributors' vaults.
            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be a ticket distribution transaction." using (output is noScalpState)
                    val nonblank = output as noScalpState
                    "The event name must not be blank." using (nonblank.event.isNotBlank())
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}