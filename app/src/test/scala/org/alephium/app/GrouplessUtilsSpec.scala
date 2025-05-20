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

import scala.collection.mutable
import scala.language.implicitConversions

import org.alephium.api.model.{AssetOutput => _, Transaction => _, Val => _, _}
import org.alephium.crypto.SecP256R1
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.ExtraUtxosInfo
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.TotalAmountNeeded
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

    val chainIndex              = ChainIndex(GroupIndex.unsafe(0), GroupIndex.unsafe(0))
    val publicKeyLike           = PublicKeyLike.WebAuthn(fromPublicKey)
    lazy val fromLockupScript   = LockupScript.p2pk(publicKeyLike, chainIndex.from)
    val fromAddress             = Address.Asset(fromLockupScript)
    val fromAddressWithGroup    = AddressLike.from(fromLockupScript)
    val fromAddressWithoutGroup = AddressLike.fromP2PKPublicKey(publicKeyLike)

    def allLockupScripts: AVector[LockupScript.P2PK] = {
      brokerConfig.cliqueGroups.fold(AVector.empty[LockupScript.P2PK]) { case (acc, group) =>
        if (group == chainIndex.from) {
          acc
        } else {
          acc :+ LockupScript.p2pk(publicKeyLike, group)
        }
      } :+ fromLockupScript
    }

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
      balances.totalAlph is alphAmount
      balances.totalTokens is AVector(tokenId -> tokenAmount)
    }

    def getBalance(lockupScript: LockupScript): (U256, U256) = {
      val balance     = blockFlow.getBalance(lockupScript, Int.MaxValue, false).rightValue
      val tokenAmount = balance.totalTokens.find(_._1 == tokenId).map(_._2).getOrElse(U256.Zero)
      (balance.totalAlph, tokenAmount)
    }

    def getBalance(addressLike: AddressLike): (U256, U256) = {
      addressLike.lockupScriptResult match {
        case LockupScript.CompleteLockupScript(lockupScript) =>
          getBalance(lockupScript)
        case halfDecodedLockupScript: LockupScript.HalfDecodedLockupScript =>
          val balance =
            serverUtils.getGrouplessBalance(blockFlow, halfDecodedLockupScript, false).rightValue
          val tokenAmount = balance.tokenBalances.flatMap(_.find(_.id == tokenId).map(_.amount))
          (balance.balance.value, tokenAmount.getOrElse(U256.Zero))
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

    private def buildGrouplessTransferTx(query: BuildTransferTx) = {
      val result = serverUtils
        .buildTransferTransaction(blockFlow, query)
        .rightValue
        .asInstanceOf[BuildGrouplessTransferTxResult]
      val txs = result.transferTxs :+ result.transferTx
      txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx.unsignedTx)).rightValue)
    }

    def testTransfer(
        alphTransferAmount: U256,
        tokenTransferAmount: U256,
        group: Option[GroupIndex],
        expectedTxSize: Int,
        destinationSize: Int = 1
    ) = {
      val groupIndex = groupIndexGen.sample.get
      val destinations = AVector.fill(destinationSize) {
        val toAddress = Address.Asset(assetLockupGen(groupIndex).sample.get)
        Destination(
          toAddress,
          Some(Amount(alphTransferAmount)),
          Some(AVector(Token(tokenId, tokenTransferAmount)))
        )
      }

      val query = BuildTransferTx(
        fromPublicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLWebAuthn),
        group = group,
        destinations = destinations
      )

      val txs = buildGrouplessTransferTx(query)
      txs.length is expectedTxSize

      val fromBalance0 = getBalance(fromAddressWithoutGroup)
      txs.foreach(tx => mineWithTx(signWithWebAuthn(tx, fromPrivateKey)._2))
      val fromBalance1 = getBalance(fromAddressWithoutGroup)

      val gasFee                   = txs.fold(U256.Zero)((acc, tx) => acc.addUnsafe(tx.gasFee))
      val totalAlphTransferAmount  = alphTransferAmount * destinationSize
      val totalTokenTransferAmount = tokenTransferAmount * destinationSize
      fromBalance0._1 is fromBalance1._1.addUnsafe(totalAlphTransferAmount).addUnsafe(gasFee)
      fromBalance0._2 is fromBalance1._2.addUnsafe(totalTokenTransferAmount)

      destinations.foreach { destination =>
        val toBalance = getBalance(AddressLike.from(destination.address.lockupScript))
        toBalance._1 is alphTransferAmount
        toBalance._2 is tokenTransferAmount
      }
    }

    def failTransfer(
        alphTransferAmount: U256,
        tokenTransferAmount: Option[U256],
        group: Option[GroupIndex],
        destinationSize: Int,
        expectedError: String
    ) = {
      val groupIndex = groupIndexGen.sample.get
      val destinations = AVector.fill(destinationSize) {
        val toAddress = Address.Asset(assetLockupGen(groupIndex).sample.get)
        Destination(
          toAddress,
          Some(Amount(alphTransferAmount)),
          tokenTransferAmount.map(amount => AVector(Token(tokenId, amount)))
        )
      }

      val query = BuildTransferTx(
        fromPublicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLWebAuthn),
        group = group,
        destinations = destinations
      )

      serverUtils.buildTransferTransaction(blockFlow, query).leftValue.detail is expectedError
    }

    def getBalance(
        address: Address.Asset,
        outputs: AVector[AssetOutput]
    ): (U256, AVector[(TokenId, U256)]) = {
      var alphBalance   = U256.Zero
      val tokenBalances = mutable.Map.empty[TokenId, U256]
      for (output <- outputs) {
        if (output.lockupScript == address.lockupScript) {
          alphBalance = alphBalance.addUnsafe(output.amount)
          output.tokens.foreach { token =>
            val amount = tokenBalances.getOrElse(token._1, U256.Zero)
            tokenBalances.put(token._1, amount.addUnsafe(token._2))
          }
        }
      }
      (alphBalance, AVector.from(tokenBalances))
    }

    implicit def toAmount(amount: U256): Amount = Amount(amount)

    implicit class RichUnsignedTransaction(tx: UnsignedTransaction) {
      def gasFee: U256 = tx.gasPrice * tx.gasAmount
    }
  }

  it should "build a transfer tx without cross-group transfers" in {
    new Fixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new Fixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(0))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new Fixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(0))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }
  }

  it should "build a transfer tx without cross-group transfers with explicit group with enough balance" in {
    new Fixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, Some(fromLockupScript.groupIndex), 1)
    }

    new Fixture {
      val lockupScript = allLockupScripts(0)
      prepare(ALPH.alph(2), ALPH.alph(2), lockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, Some(lockupScript.groupIndex), 1)
    }

    new Fixture {
      val lockupScript = allLockupScripts(1)
      prepare(ALPH.alph(2), ALPH.alph(2), lockupScript)
      failTransfer(
        ALPH.oneAlph,
        Some(ALPH.oneAlph),
        Some(fromLockupScript.groupIndex),
        1,
        "Not enough balance: got 0, expected 1000000000000000000"
      )
    }
  }

  it should "build a transfer tx with one cross-group transfer when the from address has no balance" in new Fixture {
    prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(0))
    prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(1))
    testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 2)
  }

  it should "build a transfer tx with minimal cross-group transfers" in new Fixture {
    val indices = AVector(0, 1, 2).shuffle()
    prepare(ALPH.alph(2) / 10, ALPH.alph(1) / 10, allLockupScripts(indices(0)))
    prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(indices(1)))
    prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(indices(2)))
    testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 2)
  }

  it should "build a transfer tx with one cross-group transfer when the from address does not have enough balance" in new Fixture {
    allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
    testTransfer(ALPH.alph(2), ALPH.alph(4), None, 2)
  }

  it should "build a transfer tx with multiple cross-group transfers" in {
    new Fixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      testTransfer(ALPH.alph(4), ALPH.alph(5), None, 3)
    }

    new Fixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      failTransfer(
        ALPH.alph(4),
        Some(ALPH.alph(7)),
        None,
        3,
        s"Not enough balance: 6.002 ALPH, ${tokenId.toHexString}: 15000000000000000000"
      )
    }
  }

  it should "transfer to multiple destinations" in new Fixture {
    allLockupScripts.foreach(prepare(ALPH.alph(8), ALPH.alph(8), _))
    testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 3, 20)
  }

  it should "fail if the from address does not have enough balance when building transfer txs" in new Fixture {
    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
    failTransfer(ALPH.alph(2), Some(ALPH.alph(2)), None, 1, "Not enough balance: 0.002 ALPH")
    failTransfer(
      ALPH.oneAlph,
      Some(ALPH.alph(3)),
      None,
      1,
      s"Not enough balance: ${tokenId.toHexString}: 1000000000000000000"
    )
  }

  it should "fail if the balance is locked" in new Fixture {
    val lockTime = TimeStamp.now().plusHoursUnsafe(1)

    prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript, Some(lockTime))
    failTransfer(ALPH.alph(2), ALPH.alph(0), None, 1, "Not enough balance: 2.002 ALPH")

    prepare(ALPH.alph(2), ALPH.alph(1), allLockupScripts.head)
    failTransfer(
      ALPH.alph(2),
      Some(ALPH.alph(2)),
      None,
      1,
      s"Not enough balance: 0.002 ALPH, ${tokenId.toHexString}: 1000000000000000000"
    )
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

    private def buildGrouplessExecuteScriptTx(query: BuildExecuteScriptTx) = {
      val result = serverUtils
        .buildExecuteScriptTx(blockFlow, query)
        .rightValue
        .asInstanceOf[BuildGrouplessExecuteScriptTxResult]
      val txs = result.transferTxs.map(_.unsignedTx) :+ result.executeScriptTx.unsignedTx
      txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx)).rightValue)
    }

    def buildExecuteScriptQuery(
        alphAmount: U256,
        tokenAmount: U256
    ): BuildExecuteScriptTx = {
      val script =
        s"""
           |TxScript Main {
           |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> ALPH: $alphAmount, #${tokenId.toHexString}: $tokenAmount}()
           |}
           |$contract
           |""".stripMargin
      val compiledScript = Compiler.compileTxScript(script).rightValue
      BuildExecuteScriptTx(
        fromPublicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLWebAuthn),
        serialize(compiledScript),
        attoAlphAmount = Some(alphAmount),
        group = Some(chainIndex.from),
        tokens = Some(AVector(Token(tokenId, tokenAmount)))
      )
    }

    def testExecuteScript(alphAmount: U256, tokenAmount: U256, expectedTxSize: Int) = {
      val query = buildExecuteScriptQuery(alphAmount, tokenAmount)
      val txs   = buildGrouplessExecuteScriptTx(query)
      txs.length is expectedTxSize

      val contractBalance0 = getBalance(LockupScript.p2c(contractId))
      val accountBalance0  = getBalance(fromAddressWithoutGroup)
      txs.foreach(tx => mineWithTx(signWithWebAuthn(tx, fromPrivateKey)._2))
      val contractBalance1 = getBalance(LockupScript.p2c(contractId))
      val accountBalance1  = getBalance(fromAddressWithoutGroup)

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
      .buildExecuteScriptTx(blockFlow, query0)
      .leftValue
      .detail is "Not enough ALPH balance, requires an additional 0.504 ALPH"

    val query1 = buildExecuteScriptQuery(ALPH.oneAlph, ALPH.alph(3))
    serverUtils
      .buildExecuteScriptTx(blockFlow, query1)
      .leftValue
      .detail is s"Not enough token balances, requires additional ${tokenId.toHexString}: ${ALPH.oneAlph}"
  }

  trait BuildDeployContractTxFixture extends BuildExecuteScriptTxFixture {
    def buildDeployContractQuery(
        alphAmount: U256,
        tokenAmount: U256
    ): BuildDeployContractTx = {
      val code = BuildDeployContractTx.Code(
        Compiler.compileContract(contract).rightValue,
        AVector.empty,
        AVector.empty
      )
      BuildDeployContractTx(
        fromPublicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLWebAuthn),
        serialize(code),
        group = Some(chainIndex.from),
        initialAttoAlphAmount = Some(alphAmount),
        initialTokenAmounts = Some(AVector(Token(tokenId, tokenAmount)))
      )
    }

    private def buildGrouplessDeployContractTx(query: BuildDeployContractTx) = {
      val result = serverUtils
        .buildDeployContractTx(blockFlow, query)
        .rightValue
        .asInstanceOf[BuildGrouplessDeployContractTxResult]
      val txs         = result.transferTxs.map(_.unsignedTx) :+ result.deployContractTx.unsignedTx
      val unsignedTxs = txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx)).rightValue)
      (unsignedTxs, result.deployContractTx.contractAddress.contractId)
    }

    def testDeployContract(alphAmount: U256, tokenAmount: U256, expectedTxSize: Int) = {
      val query             = buildDeployContractQuery(alphAmount, tokenAmount)
      val (txs, contractId) = buildGrouplessDeployContractTx(query)
      txs.length is expectedTxSize

      val accountBalance0 = getBalance(fromAddressWithoutGroup)
      txs.foreach(tx => mineWithTx(signWithWebAuthn(tx, fromPrivateKey)._2))
      val contractBalance = getBalance(LockupScript.p2c(contractId))
      val accountBalance1 = getBalance(fromAddressWithoutGroup)

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
      .buildDeployContractTx(blockFlow, query0)
      .leftValue
      .detail is "Not enough ALPH balance, requires an additional 0.504 ALPH"

    val query1 = buildDeployContractQuery(ALPH.oneAlph, ALPH.alph(3))
    serverUtils
      .buildDeployContractTx(blockFlow, query1)
      .leftValue
      .detail is s"Not enough token balances, requires additional ${tokenId.toHexString}: ${ALPH.oneAlph}"
  }

  it should "get the balance of the groupless address" in new Fixture {
    allLockupScripts.length is 3

    val lockTime          = TimeStamp.now().plusHoursUnsafe(1)
    val lockupScript1     = allLockupScripts.head
    val address1WithGroup = AddressLike.from(lockupScript1)
    prepare(ALPH.alph(2), ALPH.alph(2), lockupScript1, Some(lockTime))
    val balance0 = serverUtils.getBalance(blockFlow, fromAddressWithoutGroup, true).rightValue
    balance0.balance.value is ALPH.alph(2)
    balance0.lockedBalance.value is ALPH.alph(2)
    balance0.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance0.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance0.utxoNum is 2

    val lockupScript2     = allLockupScripts(1)
    val address2WithGroup = AddressLike.from(lockupScript2)
    prepare(ALPH.alph(2), ALPH.alph(2), lockupScript2)
    val balance1 = serverUtils.getBalance(blockFlow, fromAddressWithoutGroup, true).rightValue
    balance1.balance.value is ALPH.alph(4)
    balance1.lockedBalance.value is ALPH.alph(2)
    balance1.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(4))))
    balance1.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance1.utxoNum is 4

    val lockupScript3     = allLockupScripts.last
    val address3WithGroup = AddressLike.from(lockupScript3)
    prepare(ALPH.alph(2), ALPH.alph(2), lockupScript3)
    val balance2 = serverUtils.getBalance(blockFlow, fromAddressWithoutGroup, true).rightValue
    balance2.balance.value is ALPH.alph(6)
    balance2.lockedBalance.value is ALPH.alph(2)
    balance2.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(6))))
    balance2.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance2.utxoNum is 6

    val balance3 = serverUtils.getBalance(blockFlow, fromAddressWithoutGroup, true).rightValue
    balance3.balance.value is ALPH.alph(6)
    balance3.lockedBalance.value is ALPH.alph(2)
    balance3.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(6))))
    balance3.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance3.utxoNum is 6

    val balance4 = serverUtils.getBalance(blockFlow, address1WithGroup, true).rightValue
    balance4.balance.value is ALPH.alph(2)
    balance4.lockedBalance.value is ALPH.alph(2)
    balance4.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance4.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance4.utxoNum is 2

    val balance5 = serverUtils.getBalance(blockFlow, address2WithGroup, true).rightValue
    balance5.balance.value is ALPH.alph(2)
    balance5.lockedBalance.value is ALPH.alph(0)
    balance5.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance5.lockedTokenBalances is None
    balance5.utxoNum is 2

    val balance6 = serverUtils.getBalance(blockFlow, address3WithGroup, true).rightValue
    balance6.balance.value is ALPH.alph(2)
    balance6.lockedBalance.value is ALPH.alph(0)
    balance6.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance6.lockedTokenBalances is None
    balance6.utxoNum is 2
  }

  trait BuildGrouplessTransferTxWithEachGroupedAddressFixture extends Fixture {
    def testTransferWithEachGroupedAddress(
        toAddress: Address.Asset,
        alphAmount: U256,
        tokenAmount: U256
    ) = {
      val destination = Destination(
        address = toAddress,
        attoAlphAmount = Some(Amount(alphAmount)),
        tokens = Some(AVector(Token(tokenId, tokenAmount)))
      )
      val outputInfos = serverUtils.prepareOutputInfos(AVector(destination))
      val totalAmountNeeded = blockFlow
        .checkAndCalcTotalAmountNeeded(
          fromLockupScript,
          outputInfos,
          None,
          nonCoinbaseMinGasPrice
        )
        .rightValue

      serverUtils
        .buildGrouplessTransferTxWithEachGroupedAddress(
          blockFlow,
          fromLockupScript,
          outputInfos,
          totalAmountNeeded,
          nonCoinbaseMinGasPrice,
          None
        )
    }

    def verifyBalance(
        toAddress: Address.Asset,
        rawUnsignedTx: String,
        alphAmount: U256,
        tokenAmount: U256
    ) = {
      val unsignedTx = deserialize[UnsignedTransaction](Hex.unsafe(rawUnsignedTx)).rightValue
      val (alphBalance, tokenBalances) = getBalance(toAddress, unsignedTx.fixedOutputs)
      alphBalance is alphAmount
      tokenBalances is AVector((tokenId, tokenAmount))
    }

    def testTransferWithEnoughBalance(alphAmount: U256, tokenAmount: U256) = {
      val toAddress = Address.Asset(assetLockupGen(chainIndex.from).sample.get)
      val result =
        testTransferWithEachGroupedAddress(toAddress, alphAmount, tokenAmount).rightValue.rightValue
      verifyBalance(toAddress, result.transferTx.unsignedTx, alphAmount, tokenAmount)
    }

    def transferWithoutEnoughBalance(alphAmount: U256, tokenAmount: U256) = {
      val toAddress = Address.Asset(assetLockupGen(chainIndex.from).sample.get)
      testTransferWithEachGroupedAddress(toAddress, alphAmount, tokenAmount).rightValue.leftValue
    }
  }

  it should "test buildGrouplessTransferTxWithEachGroupedAddress" in {
    new BuildGrouplessTransferTxWithEachGroupedAddressFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(0))
      testTransferWithEnoughBalance(ALPH.alph(1), ALPH.alph(1))
    }

    new BuildGrouplessTransferTxWithEachGroupedAddressFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(1))
      testTransferWithEnoughBalance(ALPH.alph(1), ALPH.alph(1))
    }

    new BuildGrouplessTransferTxWithEachGroupedAddressFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(2))
      testTransferWithEnoughBalance(ALPH.alph(1), ALPH.alph(1))
    }

    new BuildGrouplessTransferTxWithEachGroupedAddressFixture {
      prepare(ALPH.alph(2).addUnsafe(dustUtxoAmount), ALPH.alph(2), allLockupScripts(1))
      prepare(ALPH.alph(1).addUnsafe(dustUtxoAmount), ALPH.alph(1), allLockupScripts(2))

      val buildingGrouplessTransferTxs = transferWithoutEnoughBalance(ALPH.alph(3), ALPH.alph(3))
      buildingGrouplessTransferTxs.length is 3

      buildingGrouplessTransferTxs(0).from is allLockupScripts(1)
      buildingGrouplessTransferTxs(0).remainingAmounts._1 is ALPH.alphFromString("1.501 ALPH").get
      buildingGrouplessTransferTxs(0).remainingAmounts._2 is AVector((tokenId, ALPH.alph(1)))
      buildingGrouplessTransferTxs(0).remainingLockupScripts is AVector(
        allLockupScripts(2),
        allLockupScripts(0)
      )

      buildingGrouplessTransferTxs(1).from is allLockupScripts(2)
      buildingGrouplessTransferTxs(1).remainingAmounts._1 is ALPH.alphFromString("2.501 ALPH").get
      buildingGrouplessTransferTxs(1).remainingAmounts._2 is AVector((tokenId, ALPH.alph(2)))
      buildingGrouplessTransferTxs(1).remainingLockupScripts is AVector(
        allLockupScripts(1),
        allLockupScripts(0)
      )

      buildingGrouplessTransferTxs(2).from is allLockupScripts(0)
      buildingGrouplessTransferTxs(2).remainingAmounts._1 is ALPH.alphFromString("3.502 ALPH").get
      buildingGrouplessTransferTxs(2).remainingAmounts._2 is AVector((tokenId, ALPH.alph(3)))
      buildingGrouplessTransferTxs(2).remainingLockupScripts is AVector(
        allLockupScripts(1),
        allLockupScripts(2)
      )
    }
  }

  it should "test tryBuildGrouplessTransferTxFromSingleGroupedAddress" in {
    new BuildGrouplessTransferTxWithEachGroupedAddressFixture {
      prepare(ALPH.alph(2), ALPH.alph(3), allLockupScripts(0))
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(1))
      prepare(ALPH.alph(3), ALPH.alph(2), allLockupScripts(2))

      val toAddress = Address.Asset(assetLockupGen(chainIndex.from).sample.get)

      val alphAmount  = ALPH.alph(3)
      val tokenAmount = ALPH.alph(5)

      val destination = Destination(
        address = toAddress,
        attoAlphAmount = Some(Amount(alphAmount)),
        tokens = Some(AVector(Token(tokenId, tokenAmount)))
      )
      val outputInfos = serverUtils.prepareOutputInfos(AVector(destination))
      val totalAmountNeeded = blockFlow
        .checkAndCalcTotalAmountNeeded(
          fromLockupScript,
          outputInfos,
          None,
          nonCoinbaseMinGasPrice
        )
        .rightValue

      val buildingGrouplessTransferTxs = serverUtils
        .buildGrouplessTransferTxWithEachGroupedAddress(
          blockFlow,
          fromLockupScript,
          outputInfos,
          totalAmountNeeded,
          nonCoinbaseMinGasPrice,
          None
        )
        .rightValue
        .leftValue

      def verifyFinalResult(
          currentBuildingGrouplessTx: GrouplessUtils.BuildingGrouplessTransferTx
      ) = {
        val result = serverUtils
          .tryBuildGrouplessTransferTxFromSingleGroupedAddress(
            blockFlow,
            nonCoinbaseMinGasPrice,
            None,
            outputInfos,
            totalAmountNeeded,
            currentBuildingGrouplessTx
          )
          .rightValue
          .rightValue

        result.transferTxs.length is 1
        verifyBalance(toAddress, result.transferTx.unsignedTx, alphAmount, tokenAmount)
      }

      buildingGrouplessTransferTxs(0).from is allLockupScripts(0)
      buildingGrouplessTransferTxs(0).remainingLockupScripts is AVector(
        allLockupScripts(2),
        allLockupScripts(1)
      )
      verifyFinalResult(buildingGrouplessTransferTxs(0))

      buildingGrouplessTransferTxs(1).from is allLockupScripts(2)
      buildingGrouplessTransferTxs(1).remainingLockupScripts is AVector(
        allLockupScripts(0),
        allLockupScripts(1)
      )
      verifyFinalResult(buildingGrouplessTransferTxs(1))

      buildingGrouplessTransferTxs(2).from is allLockupScripts(1)
      buildingGrouplessTransferTxs(2).remainingLockupScripts is AVector(
        allLockupScripts(0),
        allLockupScripts(2)
      )

      val nextBuildingGrouplessTransferTxs = serverUtils
        .tryBuildGrouplessTransferTxFromSingleGroupedAddress(
          blockFlow,
          nonCoinbaseMinGasPrice,
          None,
          outputInfos,
          totalAmountNeeded,
          buildingGrouplessTransferTxs(2)
        )
        .rightValue
        .leftValue

      nextBuildingGrouplessTransferTxs.length is 2
      nextBuildingGrouplessTransferTxs(0).from is allLockupScripts(1)
      nextBuildingGrouplessTransferTxs(0).remainingLockupScripts is AVector(allLockupScripts(2))
      nextBuildingGrouplessTransferTxs(0).remainingAmounts is (ALPH.nanoAlph(
        2000000
      ), AVector.empty)
      nextBuildingGrouplessTransferTxs(1).from is allLockupScripts(1)
      nextBuildingGrouplessTransferTxs(1).remainingLockupScripts is AVector(allLockupScripts(0))
      nextBuildingGrouplessTransferTxs(1).remainingAmounts is (ALPH.nanoAlph(0), AVector(
        (tokenId, ALPH.alph(1))
      ))
    }
  }

  it should "sortedGroupedLockupScripts" in new Fixture {
    val totalAmountNeeded = TotalAmountNeeded(
      ALPH.alph(2),
      AVector(tokenId -> ALPH.alph(2)),
      1
    )

    prepare(ALPH.alph(2), ALPH.alph(1), allLockupScripts(0))
    prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(1))
    prepare(ALPH.alph(3), ALPH.alph(2), allLockupScripts(2))

    val sortedGroupedLockupScripts = serverUtils
      .sortedGroupedLockupScripts(
        blockFlow,
        allLockupScripts,
        totalAmountNeeded,
        None
      )
      .rightValue

    sortedGroupedLockupScripts.length is 3
    sortedGroupedLockupScripts.map(_._1) is AVector(
      allLockupScripts(2),
      allLockupScripts(1),
      allLockupScripts(0)
    )
  }

  it should "test checkEnoughBalance" in new Fixture {
    val totalAmountNeeded = TotalAmountNeeded(
      ALPH.alph(2),
      AVector(tokenId -> ALPH.alph(2)),
      1
    )

    val balances0 = AVector((ALPH.alph(3), AVector((tokenId, ALPH.alph(5)))))
    serverUtils.checkEnoughBalance(totalAmountNeeded, balances0).rightValue is ()

    val balances1 = AVector(
      (ALPH.alph(1), AVector((tokenId, ALPH.alph(2)))),
      (ALPH.alph(2), AVector((tokenId, ALPH.alph(3))))
    )
    serverUtils.checkEnoughBalance(totalAmountNeeded, balances1).rightValue is ()

    val balances2 = AVector((ALPH.alph(2), AVector((tokenId, ALPH.alph(1)))))
    serverUtils
      .checkEnoughBalance(totalAmountNeeded, balances2)
      .leftValue
      .detail is s"Not enough balance: ${tokenId.toHexString}: ${ALPH.alph(1)}"

    val balances3 = AVector((ALPH.alph(1), AVector((tokenId, ALPH.alph(2)))))
    serverUtils
      .checkEnoughBalance(totalAmountNeeded, balances3)
      .leftValue
      .detail is s"Not enough balance: 1 ALPH"

    val balances4 = AVector((ALPH.alph(1), AVector((tokenId, ALPH.alph(1)))))
    serverUtils
      .checkEnoughBalance(totalAmountNeeded, balances4)
      .leftValue
      .detail is s"Not enough balance: 1 ALPH, ${tokenId.toHexString}: ${ALPH.alph(1)}"
  }
}
