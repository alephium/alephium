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

import scala.language.implicitConversions

import org.alephium.api.model.{Transaction => _, Val => _, _}
import org.alephium.crypto.SecP256R1
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.ExtraUtxosInfo
import org.alephium.protocol.ALPH
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.serde.deserialize
import org.alephium.util.{AlephiumSpec, AVector, Hex, U256}

class GrouplessUtilsSpec extends AlephiumSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  trait Fixture extends FlowFixture with ApiConfigFixture with ModelGenerators {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val serverUtils                     = new ServerUtils
    val (fromPrivateKey, fromPublicKey) = SecP256R1.generatePriPub()

    val fromLockupScript = LockupScript.p2pk(PublicKeyLike.Passkey(fromPublicKey), None)
    val fromAddress      = Address.Asset(fromLockupScript)
    val chainIndex       = ChainIndex(fromLockupScript.groupIndex, fromLockupScript.groupIndex)
    val allLockupScripts = brokerConfig.cliqueGroups.fold(AVector.empty[LockupScript.Asset]) {
      case (acc, group) =>
        if (group == chainIndex.from) {
          acc
        } else {
          acc :+ LockupScript.p2pk(fromLockupScript.publicKey, Some(group))
        }
    } :+ fromLockupScript

    val (genesisPrivateKey, genesisPublicKey, _) = genesisKeys(chainIndex.from.value)
    val tokenId                                  = issueToken()

    private def issueToken(): TokenId = {
      val tokenContract = "Contract Foo() { pub fn foo() -> () {} }"
      val issuanceInfo = Some(
        TokenIssuance.Info(Val.U256(U256.MaxValue), Some(LockupScript.p2pkh(genesisPublicKey)))
      )
      val contractId =
        createContract(tokenContract, tokenIssuanceInfo = issuanceInfo, chainIndex = chainIndex)._1
      TokenId.from(contractId)
    }

    def prepare(alphAmount: U256, tokenAmount: U256, toLockupScript: LockupScript.Asset) = {
      assume(alphAmount >= dustUtxoAmount)
      val alphRemain = alphAmount.subUnsafe(dustUtxoAmount)
      val tokenOutputInfo = UnsignedTransaction.TxOutputInfo(
        toLockupScript,
        dustUtxoAmount,
        AVector(tokenId -> tokenAmount),
        None
      )
      val outputInfos = if (alphRemain.isZero) {
        AVector(tokenOutputInfo)
      } else {
        assume(alphRemain >= dustUtxoAmount)
        AVector(
          UnsignedTransaction
            .TxOutputInfo(
              toLockupScript,
              alphAmount.subUnsafe(dustUtxoAmount),
              AVector.empty,
              None
            ),
          tokenOutputInfo
        )
      }
      val unsignedTx = blockFlow
        .transfer(
          genesisPublicKey,
          outputInfos,
          None,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          ExtraUtxosInfo.empty
        )
        .rightValue
        .rightValue
      mineWithTx(Transaction.from(unsignedTx, genesisPrivateKey))
      val balances = blockFlow.getBalance(toLockupScript, Int.MaxValue, false).rightValue
      balances._1 is alphAmount
      balances._3 is AVector(tokenId -> tokenAmount)
    }

    private def getAlphAndTokenBalance(lockupScript: LockupScript.Asset) = {
      val (alph, _, tokens, _, _) =
        blockFlow.getBalance(lockupScript, Int.MaxValue, false).rightValue
      (alph, tokens.find(_._1 == tokenId).map(_._2).getOrElse(U256.Zero))
    }

    def getBalance(lockupScript: LockupScript.Asset) = {
      lockupScript match {
        case origin: LockupScript.P2PK =>
          brokerConfig.cliqueGroups.fold((U256.Zero, U256.Zero)) {
            case ((alphAcc, tokenAcc), groupIndex) =>
              val lockupScript  = LockupScript.p2pk(origin.publicKey, Some(groupIndex))
              val (alph, token) = getAlphAndTokenBalance(lockupScript)
              (alphAcc.addUnsafe(alph), tokenAcc.addUnsafe(token))
          }
        case _ => getAlphAndTokenBalance(lockupScript)
      }
    }

    private def mineWithTx(tx: Transaction) = {
      val block = mineWithTxs(blockFlow, tx.chainIndex, AVector(tx))
      addAndCheck(blockFlow, block)
      if (!tx.chainIndex.isIntraGroup) {
        addAndCheck(
          blockFlow,
          emptyBlock(blockFlow, ChainIndex(tx.chainIndex.from, tx.chainIndex.from))
        )
      }
    }

    private def buildGrouplessTransferTx(query: BuildGrouplessTransferTx) = {
      val txs = serverUtils.buildGrouplessTransferTx(blockFlow, query).rightValue
      txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx.unsignedTx)).rightValue)
    }

    def testTransfer(
        alphTransferAmount: U256,
        tokenTransferAmount: U256,
        expectedTxSize: Int,
        destinationSize: Int = 1
    ) = {
      val groupIndex = groupIndexGen.sample.get
      val destinations = AVector.fill(destinationSize) {
        val toAddress = Address.Asset(assetLockupGen(groupIndex).sample.get)
        Destination(
          toAddress,
          alphTransferAmount,
          Some(AVector(Token(tokenId, tokenTransferAmount)))
        )
      }
      val query = BuildGrouplessTransferTx(fromAddress, destinations)

      val txs = buildGrouplessTransferTx(query)
      txs.length is expectedTxSize

      val fromBalance0 = getBalance(fromLockupScript)
      txs.foreach(tx => mineWithTx(signWithPasskey(tx, fromPrivateKey)))
      val fromBalance1 = getBalance(fromLockupScript)

      val gasFee                   = txs.fold(U256.Zero)((acc, tx) => acc.addUnsafe(tx.gasFee))
      val totalAlphTransferAmount  = alphTransferAmount * destinationSize
      val totalTokenTransferAmount = tokenTransferAmount * destinationSize
      fromBalance0._1 is fromBalance1._1.addUnsafe(totalAlphTransferAmount).addUnsafe(gasFee)
      fromBalance0._2 is fromBalance1._2.addUnsafe(totalTokenTransferAmount)

      destinations.foreach { destination =>
        val toBalance = getBalance(destination.address.lockupScript)
        toBalance._1 is alphTransferAmount
        toBalance._2 is tokenTransferAmount
      }
    }

    implicit def toAmount(amount: U256): Amount = Amount(amount)

    implicit class RichUnsignedTransaction(tx: UnsignedTransaction) {
      def gasFee: U256 = tx.gasPrice * tx.gasAmount
    }
  }

  it should "build a transfer tx without cross-group transfers" in new Fixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    testTransfer(ALPH.oneAlph, ALPH.oneAlph, 1)
  }

  it should "build a transfer tx with one cross-group transfer when the from address has no balance" in new Fixture {
    prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts.head)
    testTransfer(ALPH.oneAlph, ALPH.oneAlph, 2)
  }

  it should "build a transfer tx with one cross-group transfer when the from address does not have enough balance" in new Fixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testTransfer(ALPH.alph(2), ALPH.alph(4), 2)
  }

  it should "build a transfer tx with multiple cross-group transfers" in new Fixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testTransfer(ALPH.alph(4), ALPH.alph(5), 3)
  }

  it should "transfer to multiple destinations" in new Fixture {
    allLockupScripts.foreach(prepare(ALPH.alph(8), ALPH.alph(8), _))
    testTransfer(ALPH.oneAlph, ALPH.oneAlph, 3, 20)
  }

  it should "fail if the from address does not have enough balance" in new Fixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    val toAddress = Address.Asset(assetLockupGen(groupIndexGen.sample.get).sample.get)
    val destination0 =
      Destination(toAddress, ALPH.alph(2), Some(AVector(Token(tokenId, ALPH.alph(2)))))
    val query0 = BuildGrouplessTransferTx(fromAddress, AVector(destination0))
    serverUtils
      .buildGrouplessTransferTx(blockFlow, query0)
      .leftValue
      .detail is "Not enough ALPH balance, requires an additional 0.502 ALPH"

    val destination1 =
      Destination(toAddress, ALPH.oneAlph, Some(AVector(Token(tokenId, ALPH.alph(3)))))
    val query1 = BuildGrouplessTransferTx(fromAddress, AVector(destination1))
    serverUtils
      .buildGrouplessTransferTx(blockFlow, query1)
      .leftValue
      .detail is s"Not enough token balances, requires additional ${tokenId.toHexString}: ${ALPH.oneAlph}"
  }
}
