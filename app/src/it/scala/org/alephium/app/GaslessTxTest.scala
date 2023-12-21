// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import org.alephium.api.model._
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{dustUtxoAmount, TokenId}
import org.alephium.util._

class GaslessTxTest extends AlephiumActorSpec {
  it should "only pay gas when users is in posession of certain token" in new WalletFixture{
    val contractDeployer = wallets.head
    val subsidizeGasForTokenHolderContract =
      s"""
         |Contract SubsidizeGasForTokenHolderContract() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn subsidizeGasBasedOnTokenAmount() -> () {
         |    let callerTokenAmount = tokenRemaining!(callerAddress!(), selfContractId!())
         |    if (callerTokenAmount >= 100) {
         |       payGasFee!{selfAddress!() -> ALPH: txGasFee!()}()
         |    } else if (callerTokenAmount > 10) {
         |       payGasFee!{selfAddress!() -> ALPH: txGasFee!() / 2}()
         |    } else { }
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn getTokens(amount: U256) -> () {
         |     transferTokenFromSelf!(callerAddress!(), selfTokenId!(), amount)
         |  }
         | }
      """.stripMargin

    var contractAlphBalance = ALPH.alph(100)
    val deployResult = contract(
      contractDeployer,
      subsidizeGasForTokenHolderContract,
      None,
      None,
      Some(500),
      Some(contractAlphBalance)
    )

    def getTokens(
      wallet: Wallet,
      amount: U256
    ): SubmitTxResult = {
      val getTokenScript =
         s"""
            |TxScript GetTokens {
            |  SubsidizeGasForTokenHolderContract(#${deployResult.contractAddress.toBase58}).getTokens(${amount})
            |}
           $subsidizeGasForTokenHolderContract
         """.stripMargin
      script(
        wallet.publicKey.toHexString,
        getTokenScript,
        wallet.creation.walletName,
        attoAlphAmount = Some(Amount(dustUtxoAmount))
      )
    }

    def subsidizeGasBasedOnTokenAmount(
      wallet: Wallet,
      tokenAmount: U256
    ): SubmitTxResult = {
      val interactScript =
         s"""
            |TxScript Interact {
            |  SubsidizeGasForTokenHolderContract(#${deployResult.contractAddress.toBase58})
            |    .subsidizeGasBasedOnTokenAmount{callerAddress!() -> #${deployResult.contractId.toHexString}: ${tokenAmount}}()
            |}
           $subsidizeGasForTokenHolderContract
         """.stripMargin
      script(
        wallet.publicKey.toHexString,
        interactScript,
        wallet.creation.walletName,
        attoAlphAmount = Some(Amount(dustUtxoAmount)),
        tokens = Some((TokenId.from(deployResult.contractId), tokenAmount))
      )
    }

    def checkBalance(
        address: String,
        alphBalance: U256,
        tokenBalance: Option[U256]
    ) = {
       eventually {
         val balance = request[Balance](getBalance(address), restPort)
         balance.tokenBalances.flatMap(_.find(_.id.value == deployResult.contractId.value)).map(_.amount) is tokenBalance
         balance.balance.value is alphBalance
       }
    }

    val gasFee = ALPH.nanoAlph(10000000)
    val initialWalletBalance = ALPH.alph(1000)

    val wallet1 = wallets(1)
    val wallet1TokenBalance = U256.unsafe(101)
    getTokens(wallet1, wallet1TokenBalance)
    val wallet1AlphBalance = initialWalletBalance.subUnsafe(gasFee)
    checkBalance(wallet1.activeAddress, wallet1AlphBalance, Some(wallet1TokenBalance))

    val wallet2 = wallets(2)
    val wallet2TokenBalance = U256.unsafe(49)
    getTokens(wallet2, wallet2TokenBalance)
    var wallet2AlphBalance = initialWalletBalance.subUnsafe(gasFee)
    checkBalance(wallet2.activeAddress, wallet2AlphBalance, Some(wallet2TokenBalance))

    val wallet3 = wallets(3)
    val wallet3TokenBalance = U256.unsafe(5)
    var wallet3AlphBalance = initialWalletBalance.subUnsafe(gasFee)
    getTokens(wallet3, wallet3TokenBalance)
    checkBalance(wallet3.activeAddress, wallet3AlphBalance, Some(wallet3TokenBalance))

    subsidizeGasBasedOnTokenAmount(wallet1, wallet1TokenBalance)
    contractAlphBalance = contractAlphBalance.subUnsafe(gasFee)
    checkBalance(wallet1.activeAddress, wallet1AlphBalance, Some(wallet1TokenBalance))
    checkBalance(deployResult.contractAddress.toBase58, contractAlphBalance, Some(345))

    subsidizeGasBasedOnTokenAmount(wallet2, wallet2TokenBalance)
    wallet2AlphBalance = wallet2AlphBalance.subUnsafe(gasFee / 2)
    contractAlphBalance = contractAlphBalance.subUnsafe(gasFee / 2)
    checkBalance(wallet2.activeAddress, wallet2AlphBalance, Some(wallet2TokenBalance))
    checkBalance(deployResult.contractAddress.toBase58, contractAlphBalance, Some(345))

    subsidizeGasBasedOnTokenAmount(wallet3, wallet3TokenBalance)
    wallet3AlphBalance = wallet3AlphBalance.subUnsafe(gasFee)
    checkBalance(wallet3.activeAddress, wallet3AlphBalance, Some(wallet3TokenBalance))
    checkBalance(deployResult.contractAddress.toBase58, contractAlphBalance, Some(345))

    clique.selfClique().nodes.foreach { peer =>
      request[Boolean](stopMining, peer.restPort) is true
    }
    clique.stop()
  }
}
