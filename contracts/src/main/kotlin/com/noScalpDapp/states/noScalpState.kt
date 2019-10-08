package com.noScalpDapp.states

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.*
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import com.noScalpDapp.contracts.noScalpContract

// *********
// * State *
// *********
// Registers an on-ledger fact that the tickets have been distributed
// from one verified distributor to another.
@BelongsToContract(noScalpContract::class)
data class noScalpState(val ticket: Int,
                            val event: String,
                            val fromDistributor: Party,
                            val toDistributor: Party) : ContractState {
    override val participants get() = listOf(fromDistributor, toDistributor)
}