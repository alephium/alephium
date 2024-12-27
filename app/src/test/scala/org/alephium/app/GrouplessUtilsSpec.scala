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
import org.alephium.ralph.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

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

    def prepare(
        alphAmount: U256,
        tokenAmount: U256,
        toLockupScript: LockupScript.Asset,
        lockTime: Option[TimeStamp] = None
    ) = {
      assume(alphAmount >= dustUtxoAmount)
      val alphRemain = alphAmount.subUnsafe(dustUtxoAmount)
      val tokenOutputInfo = UnsignedTransaction.TxOutputInfo(
        toLockupScript,
        dustUtxoAmount,
        AVector(tokenId -> tokenAmount),
        lockTime
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
              lockTime
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

    private def getAlphAndTokenBalance(lockupScript: LockupScript) = {
      val (alph, _, tokens, _, _) =
        blockFlow.getBalance(lockupScript, Int.MaxValue, false).rightValue
      (alph, tokens.find(_._1 == tokenId).map(_._2).getOrElse(U256.Zero))
    }

    def getBalance(lockupScript: LockupScript) = {
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

    def mineWithTx(tx: Transaction) = {
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

  it should "fail if the from address does not have enough balance when building transfer txs" in new Fixture {
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

  it should "fail if the balance is locked" in new Fixture {
    val toAddress = Address.Asset(assetLockupGen(groupIndexGen.sample.get).sample.get)

    val lockTime = TimeStamp.now().plusHoursUnsafe(1)
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript, Some(lockTime))
    val destination0 = Destination(toAddress, ALPH.alph(1), None)
    val query0       = BuildGrouplessTransferTx(fromAddress, AVector(destination0))
    serverUtils
      .buildGrouplessTransferTx(blockFlow, query0)
      .leftValue
      .detail is "Not enough ALPH balance, requires an additional 1.501 ALPH"

    prepare(ALPH.alph(2), ALPH.alph(1), allLockupScripts.head)
    val destination1 =
      Destination(toAddress, ALPH.alph(1), Some(AVector(Token(tokenId, ALPH.alph(2)))))
    val query1 = BuildGrouplessTransferTx(fromAddress, AVector(destination1))
    serverUtils
      .buildGrouplessTransferTx(blockFlow, query1)
      .leftValue
      .detail is s"Not enough token balances, requires additional ${tokenId.toHexString}: ${ALPH.oneAlph}"
  }

  trait BuildExecuteScriptTxFixture extends Fixture {
    val contract =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn foo() -> () {
         |    let alphAmount = tokenRemaining!(callerAddress!(), ALPH)
         |    let tokenAmount = tokenRemaining!(callerAddress!(), #${tokenId.toHexString})
         |    transferTokenToSelf!(callerAddress!(), ALPH, alphAmount)
         |    transferTokenToSelf!(callerAddress!(), #${tokenId.toHexString}, tokenAmount)
         |  }
         |}
         |""".stripMargin

    val contractId = createContract(contract, chainIndex = chainIndex)._1

    private def buildGrouplessExecuteScriptTx(query: BuildGrouplessExecuteScriptTx) = {
      val result = serverUtils.buildGrouplessExecuteScriptTx(blockFlow, query).rightValue
      val txs    = result.transferTxs.map(_.unsignedTx) :+ result.executeScriptTx.unsignedTx
      txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx)).rightValue)
    }

    def buildExecuteScriptQuery(
        alphAmount: U256,
        tokenAmount: U256
    ): BuildGrouplessExecuteScriptTx = {
      val script =
        s"""
           |TxScript Main {
           |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> ALPH: $alphAmount, #${tokenId.toHexString}: $tokenAmount}()
           |}
           |$contract
           |""".stripMargin
      val compiledScript = Compiler.compileTxScript(script).rightValue
      BuildGrouplessExecuteScriptTx(
        fromAddress,
        serialize(compiledScript),
        attoAlphAmount = Some(alphAmount),
        tokens = Some(AVector(Token(tokenId, tokenAmount)))
      )
    }

    def testExecuteScript(alphAmount: U256, tokenAmount: U256, expectedTxSize: Int) = {
      val query = buildExecuteScriptQuery(alphAmount, tokenAmount)
      val txs   = buildGrouplessExecuteScriptTx(query)
      txs.length is expectedTxSize

      val contractBalance0 = getBalance(LockupScript.p2c(contractId))
      val accountBalance0  = getBalance(fromAddress.lockupScript)
      txs.foreach(tx => mineWithTx(signWithPasskey(tx, fromPrivateKey)))
      val contractBalance1 = getBalance(LockupScript.p2c(contractId))
      val accountBalance1  = getBalance(fromAddress.lockupScript)

      val gasFee = txs.fold(U256.Zero)((acc, tx) => acc.addUnsafe(tx.gasFee))
      contractBalance1._1 is contractBalance0._1.addUnsafe(alphAmount)
      contractBalance1._2 is contractBalance0._2.addUnsafe(tokenAmount)
      accountBalance0._1 is accountBalance1._1.addUnsafe(alphAmount).addUnsafe(gasFee)
      accountBalance0._2 is accountBalance1._2.addUnsafe(tokenAmount)
    }
  }

  it should "build an execute script tx without cross-group transfers" in new BuildExecuteScriptTxFixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    testExecuteScript(ALPH.oneAlph, ALPH.oneAlph, 1)
  }

  it should "build an execute script tx with one cross-group transfer when the from address has no balance" in new BuildExecuteScriptTxFixture {
    prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts.head)
    testExecuteScript(ALPH.oneAlph, ALPH.oneAlph, 2)
  }

  it should "build an execute script tx with one cross-group transfer when the from address does not have enough balance" in new BuildExecuteScriptTxFixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testExecuteScript(ALPH.alph(2), ALPH.alph(4), 2)
  }

  it should "build an execute script tx with multiple cross-group transfers" in new BuildExecuteScriptTxFixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testExecuteScript(ALPH.alph(4), ALPH.alph(5), 3)
  }

  it should "fail if the from address does not have enough balance when building execute script txs" in new BuildExecuteScriptTxFixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    val query0 = buildExecuteScriptQuery(ALPH.alph(2), ALPH.alph(2))
    serverUtils
      .buildGrouplessExecuteScriptTx(blockFlow, query0)
      .leftValue
      .detail is "Not enough ALPH balance, requires an additional 0.504 ALPH"

    val query1 = buildExecuteScriptQuery(ALPH.oneAlph, ALPH.alph(3))
    serverUtils
      .buildGrouplessExecuteScriptTx(blockFlow, query1)
      .leftValue
      .detail is s"Not enough token balances, requires additional ${tokenId.toHexString}: ${ALPH.oneAlph}"
  }

  trait BuildDeployContractTxFixture extends BuildExecuteScriptTxFixture {
    def buildDeployContractQuery(
        alphAmount: U256,
        tokenAmount: U256
    ): BuildGrouplessDeployContractTx = {
      val code = BuildDeployContractTx.Code(
        Compiler.compileContract(contract).rightValue,
        AVector.empty,
        AVector.empty
      )
      BuildGrouplessDeployContractTx(
        fromAddress,
        serialize(code),
        initialAttoAlphAmount = Some(alphAmount),
        initialTokenAmounts = Some(AVector(Token(tokenId, tokenAmount)))
      )
    }

    private def buildGrouplessDeployContractTx(query: BuildGrouplessDeployContractTx) = {
      val result      = serverUtils.buildGrouplessDeployContractTx(blockFlow, query).rightValue
      val txs         = result.transferTxs.map(_.unsignedTx) :+ result.deployContractTx.unsignedTx
      val unsignedTxs = txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx)).rightValue)
      (unsignedTxs, result.deployContractTx.contractAddress.contractId)
    }

    def testDeployContract(alphAmount: U256, tokenAmount: U256, expectedTxSize: Int) = {
      val query             = buildDeployContractQuery(alphAmount, tokenAmount)
      val (txs, contractId) = buildGrouplessDeployContractTx(query)
      txs.length is expectedTxSize

      val accountBalance0 = getBalance(fromAddress.lockupScript)
      txs.foreach(tx => mineWithTx(signWithPasskey(tx, fromPrivateKey)))
      val contractBalance = getBalance(LockupScript.p2c(contractId))
      val accountBalance1 = getBalance(fromAddress.lockupScript)

      val gasFee = txs.fold(U256.Zero)((acc, tx) => acc.addUnsafe(tx.gasFee))
      contractBalance._1 is alphAmount
      contractBalance._2 is tokenAmount
      accountBalance0._1 is accountBalance1._1.addUnsafe(alphAmount).addUnsafe(gasFee)
      accountBalance0._2 is accountBalance1._2.addUnsafe(tokenAmount)
    }
  }

  it should "build an deploy contract tx without cross-group transfers" in new BuildDeployContractTxFixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    testDeployContract(ALPH.oneAlph, ALPH.oneAlph, 1)
  }

  it should "build an deploy contract tx with one cross-group transfer when the from address has no balance" in new BuildDeployContractTxFixture {
    prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts.head)
    testDeployContract(ALPH.oneAlph, ALPH.oneAlph, 2)
  }

  it should "build an deploy contract tx with one cross-group transfer when the from address does not have enough balance" in new BuildDeployContractTxFixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testDeployContract(ALPH.alph(2), ALPH.alph(4), 2)
  }

  it should "build an deploy contract tx with multiple cross-group transfers" in new BuildDeployContractTxFixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testDeployContract(ALPH.alph(4), ALPH.alph(5), 3)
  }

  it should "fail if the from address does not have enough balance when building deploy contract txs" in new BuildDeployContractTxFixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    val query0 = buildDeployContractQuery(ALPH.alph(2), ALPH.alph(2))
    serverUtils
      .buildGrouplessDeployContractTx(blockFlow, query0)
      .leftValue
      .detail is "Not enough ALPH balance, requires an additional 0.504 ALPH"

    val query1 = buildDeployContractQuery(ALPH.oneAlph, ALPH.alph(3))
    serverUtils
      .buildGrouplessDeployContractTx(blockFlow, query1)
      .leftValue
      .detail is s"Not enough token balances, requires additional ${tokenId.toHexString}: ${ALPH.oneAlph}"
  }
}
