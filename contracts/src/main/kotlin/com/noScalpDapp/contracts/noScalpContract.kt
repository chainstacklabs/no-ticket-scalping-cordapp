package com.noScalpDapp.contracts

import net.corda.core.contracts.*
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.transactions.LedgerTransaction
import com.noScalpDapp.states.noScalpState

// *****************
// * Contract Code *
// *****************
// Contract ID to build the transaction.
val NOSCALP_CONTRACT_ID = "com.noScalpDapp.contracts.noScalpContract"

class noScalpContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.noScalpDapp.contracts.noScalpContract"
    }
    // Create command.
    class Create : CommandData

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Create>()

        requireThat {
            // Since this contract covers ticket distribution only and not ticket issuance, put
            // constraints: there must be an output and there must not be input. Basically we are registering
            // ticket distribution and not ticket issuance.
            "Ticket distribution must not consume inputs." using (tx.inputs.isEmpty())
            "noScalpState state must have an output." using (tx.outputs.size == 1)

            // Do not allow zero ticket distribution.
            val out = tx.outputsOfType<noScalpState>().single()
            "Ticket quantity cannot be zero." using (out.ticket > 0)

            // Both Distributors must sign.
            "Both Distributors must sign the transaction" using (command.signers.toSet().size == 2)

            // There must be an event name.
            "There must be event name." using (out.event.isNotBlank())
        }
    }
}
