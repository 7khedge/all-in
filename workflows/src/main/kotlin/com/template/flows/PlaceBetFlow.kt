package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.GameContract
import com.template.states.ActionType.*
import com.template.states.Bet
import com.template.states.Game
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

// *********
// * Flows *
// *********

@InitiatingFlow
@StartableByRPC
class PlaceBetInitiator(private val action : Bet) : FlowLogic<SignedTransaction>() {

    /**
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
     * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object GENERATING_TRANSACTION : Step("Generating transaction based on new IOU.")
        object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
        object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
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

    @Suspendable
    override fun call() : SignedTransaction {
        // Obtain a reference to the notary we want to use.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // Stage 1.
        progressTracker.currentStep = GENERATING_TRANSACTION
        val currentGame = serviceHub.vaultService.queryBy(Game::class.java).states.firstOrNull() ?: throw IllegalArgumentException("Could find game in ledger")


        var (newGame, txCommand) = applyAction(currentGame, action)

        val txBuilder = TransactionBuilder(notary)
                .addOutputState(newGame, GameContract.ID)
                .addCommand(txCommand)

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
        // Send the state to other players, and receive it back with their signature.
        val sessions = currentGame.state.data.allExceptOwner().map { initiateFlow(it) }
        val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, sessions, GATHERING_SIGS.childProgressTracker()))

        // Stage 5.
        progressTracker.currentStep = FINALISING_TRANSACTION
        // Notarise and record the transaction in all players and dealer vaults.
        return subFlow(FinalityFlow(fullySignedTx, sessions, FINALISING_TRANSACTION.childProgressTracker()))
    }

    private fun applyAction(currentGameStateRef: StateAndRef<Game>, bet: Bet) : Pair<Game, Command<GameContract.Commands.PlaceBet>> {
        val currentGame = currentGameStateRef.state.data
        val txCommand = Command(GameContract.Commands.PlaceBet(), currentGame.players.map { it.party.owningKey } + currentGame.dealer.party.owningKey)
        if(bet.actionType != FOLD) {
            return Pair(updateTableAccount(bet.amount, currentGame), txCommand)
        }
        return Pair(currentGame, txCommand)
    }

    private fun updateTableAccount(tableAccount : Int, currentGame : Game ) : Game =
            currentGame.copy( owner = currentGame.getNextOwner(),
                    dealer = currentGame.dealer.copy(tableAccount = tableAccount + tableAccount))

}

@InitiatedBy(PlaceBetInitiator::class)
class PlaceBetResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call() : SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                //TODO: Implement PlaceBetResponder Flow
            }
        }
        val txId = subFlow(signTransactionFlow).id

        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}


