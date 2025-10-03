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
import scala.util.Random

import org.alephium.api.{model => api}
import org.alephium.api.model.{Address => _, AssetOutput => _, Transaction => _, Val => _, _}
import org.alephium.crypto.{Byte64, ED25519, SecP256K1, SecP256R1}
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.ExtraUtxosInfo
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.TotalAmountNeeded
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

// scalastyle:off file.size.limit
class GrouplessUtilsSpec extends AlephiumSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  trait GrouplessFixture extends FlowFixture with ApiConfigFixture with ModelGenerators {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val serverUtils = new ServerUtils
    val chainIndex  = ChainIndex(GroupIndex.unsafe(0), GroupIndex.unsafe(0))
    val (genesisPrivateKey, genesisPublicKey, _) = genesisKeys(chainIndex.from.value)

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
  }

  sealed trait GrouplessTransferFixture extends GrouplessFixture {
    val tokenId = issueToken()

    private def issueToken(): TokenId = {
      val tokenContract = "Contract Foo() { pub fn foo() -> () {} }"
      val issuanceInfo = Some(
        TokenIssuance.Info(Val.U256(U256.MaxValue), Some(LockupScript.p2pkh(genesisPublicKey)))
      )
      val contractId =
        createContract(tokenContract, tokenIssuanceInfo = issuanceInfo, chainIndex = chainIndex)._1
      TokenId.from(contractId)
    }

    def getBalance(address: api.Address): (U256, U256) = {
      address.lockupScript match {
        case api.Address.CompleteLockupScript(lockupScript) =>
          getBalance(lockupScript)
        case halfDecodedLockupScript: api.Address.HalfDecodedLockupScript =>
          val balance =
            serverUtils.getGrouplessBalance(blockFlow, halfDecodedLockupScript, false).rightValue
          val tokenAmount = balance.tokenBalances.flatMap(_.find(_.id == tokenId).map(_.amount))
          (balance.balance.value, tokenAmount.getOrElse(U256.Zero))
      }
    }

    def getBalance(lockupScript: LockupScript): (U256, U256) = {
      val balance     = blockFlow.getBalance(lockupScript, Int.MaxValue, false).rightValue
      val tokenAmount = balance.totalTokens.find(_._1 == tokenId).map(_._2).getOrElse(U256.Zero)
      (balance.totalAlph, tokenAmount)
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

    def baseLockupScript: LockupScript.GroupedAsset
    def fromAddressWithoutGroup: api.Address
    def allLockupScripts: AVector[LockupScript.GroupedAsset] = {
      brokerConfig.cliqueGroups.fold(AVector.empty[LockupScript.GroupedAsset]) {
        case (acc, group) =>
          if (group == chainIndex.from) {
            acc
          } else {
            acc :+ (baseLockupScript match {
              case p2pk: LockupScript.P2PK     => LockupScript.P2PK(p2pk.publicKey, group)
              case p2hmpk: LockupScript.P2HMPK => LockupScript.P2HMPK(p2hmpk.p2hmpkHash, group)
            })
          }
      } :+ baseLockupScript
    }

    def failTransfer(
        destinations: AVector[Destination],
        group: Option[GroupIndex],
        expectedError: String
    ): Unit

    def failTransfer(
        alphTransferAmount: U256,
        tokenTransferAmount: Option[U256],
        group: Option[GroupIndex],
        destinationSize: Int,
        expectedError: String
    ): Unit = {
      val destinations =
        prepareDestinations(alphTransferAmount, tokenTransferAmount, destinationSize)
      failTransfer(destinations, group, expectedError)
    }

    def testTransfer(
        alphTransferAmount: U256,
        tokenTransferAmount: U256,
        group: Option[GroupIndex],
        expectedTxSize: Int,
        destinationSize: Int = 1
    ): Unit = {
      val destinations =
        prepareDestinations(alphTransferAmount, Some(tokenTransferAmount), destinationSize)
      val txs = buildTxs(destinations, group, expectedTxSize)

      val fromBalance0 = getBalance(fromAddressWithoutGroup)
      submitTxs(txs)
      val fromBalance1 = getBalance(fromAddressWithoutGroup)

      val gasFee                   = txs.fold(U256.Zero)((acc, tx) => acc.addUnsafe(tx.gasFee))
      val totalAlphTransferAmount  = alphTransferAmount * destinationSize
      val totalTokenTransferAmount = tokenTransferAmount * destinationSize
      fromBalance0._1 is fromBalance1._1.addUnsafe(totalAlphTransferAmount).addUnsafe(gasFee)
      fromBalance0._2 is fromBalance1._2.addUnsafe(totalTokenTransferAmount)

      destinations.foreach { destination =>
        val toBalance = getBalance(api.Address.from(destination.address.lockupScript))
        toBalance._1 is alphTransferAmount
        toBalance._2 is tokenTransferAmount
      }
    }

    def getAllUnsignedTxs(result: BuildGrouplessTransferTxResult) = {
      val txs = result.fundingTxs.getOrElse(AVector.empty).map(_.unsignedTx) :+ result.unsignedTx
      txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx)).rightValue)
    }

    def prepareDestinations(
        alphTransferAmount: U256,
        tokenTransferAmount: Option[U256],
        destinationSize: Int
    ) = {
      val groupIndex = groupIndexGen.sample.get
      AVector.fill(destinationSize) {
        val toAddress = Address.Asset(assetLockupGen(groupIndex).sample.get)
        Destination(
          toAddress,
          Some(Amount(alphTransferAmount)),
          tokenTransferAmount.map(amount => AVector(Token(tokenId, amount)))
        )
      }
    }

    def buildTxs(
        destinations: AVector[Destination],
        group: Option[GroupIndex],
        expectedTxSize: Int
    ): AVector[UnsignedTransaction]
    def submitTxs(txs: AVector[UnsignedTransaction]): Unit
  }

  trait P2PKFixture extends GrouplessTransferFixture {

    val (fromPrivateKey, fromPublicKey) = SecP256R1.generatePriPub()
    val publicKeyLike                   = PublicKeyLike.WebAuthn(fromPublicKey)
    lazy val fromLockupScript           = LockupScript.p2pk(publicKeyLike, chainIndex.from)
    lazy val baseLockupScript: LockupScript.GroupedAsset = fromLockupScript
    val fromAddress                                      = Address.Asset(fromLockupScript)
    val fromAddressWithGroup                             = api.Address.from(fromLockupScript)
    val fromAddressWithoutGroup                          = api.Address.from(publicKeyLike)

    def buildRequest(
        group: Option[GroupIndex],
        destinations: AVector[Destination]
    ): BuildTransferTx = {
      BuildTransferTx(
        fromPublicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLWebAuthn),
        group = group,
        destinations = destinations
      )
    }

    def buildTxs(
        destinations: AVector[Destination],
        group: Option[GroupIndex],
        expectedTxSize: Int
    ): AVector[UnsignedTransaction] = {
      val query = buildRequest(group, destinations)
      val txs   = buildP2PKTransferTx(query)
      txs.length is expectedTxSize
      txs
    }

    def submitTxs(txs: AVector[UnsignedTransaction]) = {
      txs.foreach(tx => mineWithTx(signWithWebAuthn(tx, fromPrivateKey)._2))
    }

    private def buildP2PKTransferTx(query: BuildTransferTx) = {
      val result = serverUtils
        .buildTransferTransaction(blockFlow, query)
        .rightValue
        .asInstanceOf[BuildGrouplessTransferTxResult]
      getAllUnsignedTxs(result)
    }

    def failTransfer(
        destinations: AVector[Destination],
        group: Option[GroupIndex],
        expectedError: String
    ) = {
      val query = buildRequest(group, destinations)
      serverUtils.buildTransferTransaction(blockFlow, query).leftValue.detail is expectedError
      ()
    }
  }

  trait P2HMPKFixture extends GrouplessTransferFixture {
    val (fromPrivateKey0, fromPublicKey0) = SecP256R1.generatePriPub()
    val publicKeyLike0                    = PublicKeyLike.WebAuthn(fromPublicKey0)
    val (fromPrivateKey1, fromPublicKey1) = SecP256K1.generatePriPub()
    val publicKeyLike1                    = PublicKeyLike.SecP256K1(fromPublicKey1)
    val (fromPrivateKey2, fromPublicKey2) = ED25519.generatePriPub()
    val publicKeyLike2                    = PublicKeyLike.ED25519(fromPublicKey2)

    val allPubicKeyLikes = AVector(publicKeyLike0, publicKeyLike1, publicKeyLike2)

    lazy val fromLockupScript = LockupScript.P2HMPK.unsafe(allPubicKeyLikes, 2, chainIndex.from)
    lazy val baseLockupScript: LockupScript.GroupedAsset = fromLockupScript
    val fromP2HMPKHash                                   = fromLockupScript.p2hmpkHash
    val fromAddress                                      = Address.Asset(fromLockupScript)
    val fromAddressWithGroup                             = api.Address.from(fromLockupScript)
    val fromAddressWithoutGroup                          = api.Address.from(fromP2HMPKHash)

    def signTx(unsignedTx: UnsignedTransaction): Transaction = {
      val (_, signedTx0) = signWithWebAuthn(unsignedTx, fromPrivateKey0)
      val signature      = Byte64.from(SecP256K1.sign(unsignedTx.id, fromPrivateKey1))
      signedTx0.copy(inputSignatures = signedTx0.inputSignatures :+ signature)
    }

    def buildRequest(
        address: api.Address,
        group: Option[GroupIndex],
        destinations: AVector[Destination]
    ): BuildMultisig = {
      BuildMultisig(
        address,
        fromPublicKeys = AVector(fromPublicKey0.bytes, fromPublicKey1.bytes, fromPublicKey2.bytes),
        fromPublicKeyTypes = Some(
          AVector(BuildTxCommon.GLWebAuthn, BuildTxCommon.GLSecP256K1, BuildTxCommon.GLED25519)
        ),
        fromPublicKeyIndexes = Some(AVector(0, 1)),
        group = group,
        destinations = destinations,
        multiSigType = Some(MultiSigType.P2HMPK)
      )
    }

    def buildTxs(
        destinations: AVector[Destination],
        group: Option[GroupIndex],
        expectedTxSize: Int
    ): AVector[UnsignedTransaction] = {
      val query = buildRequest(fromAddressWithGroup, group, destinations)
      val txs   = buildP2HMPKTransferTx(query)
      txs.length is expectedTxSize
      txs
    }

    def submitTxs(txs: AVector[UnsignedTransaction]): Unit = {
      txs.foreach(tx => mineWithTx(signTx(tx)))
    }

    private def buildP2HMPKTransferTx(query: BuildMultisig) = {
      val result = serverUtils
        .buildMultisig(blockFlow, query)
        .rightValue
        .asInstanceOf[BuildGrouplessTransferTxResult]
      getAllUnsignedTxs(result)
    }

    def failTransfer(
        destinations: AVector[Destination],
        group: Option[GroupIndex],
        expectedError: String
    ): Unit = {
      val query = buildRequest(fromAddressWithoutGroup, group, destinations)
      serverUtils.buildMultisig(blockFlow, query).leftValue.detail is expectedError
      ()
    }
  }

  it should "build a transfer tx without cross-group transfers" in {
    new P2PKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new P2PKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(0))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new P2PKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(1))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new P2HMPKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new P2HMPKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(0))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }

    new P2HMPKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), allLockupScripts(1))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 1)
    }
  }

  it should "build a transfer tx without cross-group transfers with explicit group with enough balance" in {
    new P2PKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, Some(fromLockupScript.groupIndex), 1)
    }

    new P2PKFixture {
      val lockupScript = allLockupScripts(0)
      prepare(ALPH.alph(2), ALPH.alph(2), lockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, Some(lockupScript.groupIndex), 1)
    }

    new P2PKFixture {
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

    new P2HMPKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, Some(fromLockupScript.groupIndex), 1)
    }

    new P2HMPKFixture {
      val lockupScript = allLockupScripts(0)
      prepare(ALPH.alph(2), ALPH.alph(2), lockupScript)
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, Some(lockupScript.groupIndex), 1)
    }

    new P2HMPKFixture {
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

  it should "build a transfer tx with one cross-group transfer when the from address has no balance" in {
    new P2PKFixture {
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(0))
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(1))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 2)
    }

    new P2HMPKFixture {
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(0))
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(1))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 2)
    }
  }

  it should "build a transfer tx with minimal cross-group transfers" in {
    new P2PKFixture {
      val indices = AVector(0, 1, 2).shuffle()
      prepare(ALPH.alph(2) / 10, ALPH.alph(1) / 10, allLockupScripts(indices(0)))
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(indices(1)))
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(indices(2)))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 2)
    }

    new P2HMPKFixture {
      val indices = AVector(0, 1, 2).shuffle()
      prepare(ALPH.alph(2) / 10, ALPH.alph(1) / 10, allLockupScripts(indices(0)))
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(indices(1)))
      prepare(ALPH.alph(2), ALPH.alph(1) / 2, allLockupScripts(indices(2)))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 2)
    }
  }

  it should "build a transfer tx with one cross-group transfer when the from address does not have enough balance" in {
    new P2PKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      testTransfer(ALPH.alph(2), ALPH.alph(4), None, 2)
    }

    new P2HMPKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      testTransfer(ALPH.alph(2), ALPH.alph(4), None, 2)
    }
  }

  it should "build a transfer tx with multiple cross-group transfers" in {
    new P2PKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      testTransfer(ALPH.alph(4), ALPH.alph(5), None, 3)
    }

    new P2PKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      failTransfer(
        ALPH.alph(4),
        Some(ALPH.alph(7)),
        None,
        3,
        s"Not enough balance: 7.502 ALPH, ${tokenId.toHexString}: 15000000000000000000"
      )
    }

    new P2HMPKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      testTransfer(ALPH.alph(4), ALPH.alph(5), None, 3)
    }

    new P2HMPKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(2), ALPH.alph(2), _))
      failTransfer(
        ALPH.alph(4),
        Some(ALPH.alph(7)),
        None,
        3,
        s"Not enough balance: 7.502 ALPH, ${tokenId.toHexString}: 15000000000000000000"
      )
    }
  }

  it should "transfer to multiple destinations" in {
    new P2PKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(8), ALPH.alph(8), _))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 3, 20)
    }

    new P2HMPKFixture {
      allLockupScripts.foreach(prepare(ALPH.alph(8), ALPH.alph(8), _))
      testTransfer(ALPH.oneAlph, ALPH.oneAlph, None, 3, 20)
    }
  }

  it should "fail if the from address does not have enough balance when building transfer txs" in {
    new P2PKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      failTransfer(ALPH.alph(2), Some(ALPH.alph(2)), None, 1, "Not enough balance: 0.502 ALPH")
      failTransfer(
        ALPH.oneAlph,
        Some(ALPH.alph(3)),
        None,
        1,
        s"Not enough balance: ${tokenId.toHexString}: 1000000000000000000"
      )
    }

    new P2HMPKFixture {
      prepare(ALPH.alph(2), ALPH.alph(2), fromLockupScript)
      failTransfer(ALPH.alph(2), Some(ALPH.alph(2)), None, 1, "Not enough balance: 0.502 ALPH")
      failTransfer(
        ALPH.oneAlph,
        Some(ALPH.alph(3)),
        None,
        1,
        s"Not enough balance: ${tokenId.toHexString}: 1000000000000000000"
      )
    }
  }

  it should "fail if the balance is locked" in {
    new P2PKFixture {
      prepare(
        ALPH.alph(2),
        ALPH.alph(2),
        fromLockupScript,
        Some(TimeStamp.now().plusHoursUnsafe(1))
      )
      failTransfer(ALPH.alph(2), ALPH.alph(0), None, 1, "Not enough balance: 2.502 ALPH")

      prepare(ALPH.alph(2), ALPH.alph(1), allLockupScripts.head)
      failTransfer(
        ALPH.alph(2),
        Some(ALPH.alph(2)),
        None,
        1,
        s"Not enough balance: 0.502 ALPH, ${tokenId.toHexString}: 1000000000000000000"
      )
    }

    new P2HMPKFixture {
      prepare(
        ALPH.alph(2),
        ALPH.alph(2),
        fromLockupScript,
        Some(TimeStamp.now().plusHoursUnsafe(1))
      )
      failTransfer(ALPH.alph(2), ALPH.alph(0), None, 1, "Not enough balance: 2.502 ALPH")

      prepare(ALPH.alph(2), ALPH.alph(1), allLockupScripts.head)
      failTransfer(
        ALPH.alph(2),
        Some(ALPH.alph(2)),
        None,
        1,
        s"Not enough balance: 0.502 ALPH, ${tokenId.toHexString}: 1000000000000000000"
      )
    }
  }

  it should "validate the P2HMPK transfer request" in {
    new P2HMPKFixture {
      val groupIndex = groupIndexGen.sample.get
      val toAddress  = Address.Asset(assetLockupGen(groupIndex).sample.get)
      val destinations = AVector(
        Destination(
          toAddress,
          Some(Amount(ALPH.oneAlph)),
          Some(AVector(Token(tokenId, ALPH.oneAlph)))
        )
      )

      val query = BuildMultisig(
        fromAddressWithoutGroup,
        fromPublicKeys = AVector(fromPublicKey0.bytes, fromPublicKey1.bytes, fromPublicKey2.bytes),
        fromPublicKeyTypes = Some(
          AVector(BuildTxCommon.GLWebAuthn, BuildTxCommon.GLSecP256K1, BuildTxCommon.GLED25519)
        ),
        fromPublicKeyIndexes = Some(AVector(0, 1)),
        group = Some(chainIndex.from),
        destinations = destinations,
        multiSigType = Some(MultiSigType.P2HMPK)
      )

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyTypes = Some(AVector(BuildTxCommon.GLWebAuthn)))
        )
        .leftValue
        .detail is "`keyTypes` length should be the same as `keys` length"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyTypes = None)
        )
        .leftValue
        .detail is s"Invalid public key ${Hex.toHexString(fromPublicKey2.bytes)} for keyType SecP256K1"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyTypes = Some(AVector.fill(3)(BuildTxCommon.GLED25519)))
        )
        .leftValue
        .detail is s"Invalid public key ${Hex.toHexString(fromPublicKey0.bytes)} for keyType GLED25519"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeys = AVector.empty, fromPublicKeyTypes = None)
        )
        .leftValue
        .detail is "`keys` can not be empty"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyIndexes = Some(AVector(1, 0)))
        )
        .leftValue
        .detail is "Public key indexes should be sorted in ascending order, each index should be in range [0, publicKeys.length)"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyIndexes = Some(AVector.empty))
        )
        .leftValue
        .detail is "Invalid m in m-of-n multisig: m=0, n=3"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyIndexes = Some(AVector(0, 1, 2, 3)))
        )
        .leftValue
        .detail is "Invalid m in m-of-n multisig: m=4, n=3"

      serverUtils
        .buildMultisig(
          blockFlow,
          query.copy(fromPublicKeyIndexes = Some(AVector(0, 100)))
        )
        .leftValue
        .detail is "Public key indexes should be sorted in ascending order, each index should be in range [0, publicKeys.length)"
    }
  }

  trait BuildExecuteScriptTxFixture extends P2PKFixture {
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
      val txs = result.fundingTxs.getOrElse(AVector.empty).map(_.unsignedTx) :+ result.unsignedTx
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
      val txs = result.fundingTxs.getOrElse(AVector.empty).map(_.unsignedTx) :+ result.unsignedTx
      val unsignedTxs = txs.map(tx => deserialize[UnsignedTransaction](Hex.unsafe(tx)).rightValue)
      (unsignedTxs, result.contractAddress.contractId)
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

  it should "get the balance of the groupless address" in new P2PKFixture {
    allLockupScripts.length is 3

    val lockTime          = TimeStamp.now().plusHoursUnsafe(1)
    val lockupScript1     = allLockupScripts.head
    val address1WithGroup = api.Address.from(lockupScript1)
    prepare(ALPH.alph(2), ALPH.alph(2), lockupScript1, Some(lockTime))
    val balance0 = serverUtils.getBalance(blockFlow, fromAddressWithoutGroup, true).rightValue
    balance0.balance.value is ALPH.alph(2)
    balance0.lockedBalance.value is ALPH.alph(2)
    balance0.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance0.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance0.utxoNum is 2

    val lockupScript2     = allLockupScripts(1)
    val address2WithGroup = api.Address.from(lockupScript2)
    prepare(ALPH.alph(2), ALPH.alph(2), lockupScript2)
    val balance1 = serverUtils.getBalance(blockFlow, fromAddressWithoutGroup, true).rightValue
    balance1.balance.value is ALPH.alph(4)
    balance1.lockedBalance.value is ALPH.alph(2)
    balance1.tokenBalances is Some(AVector(Token(tokenId, ALPH.alph(4))))
    balance1.lockedTokenBalances is Some(AVector(Token(tokenId, ALPH.alph(2))))
    balance1.utxoNum is 4

    val lockupScript3     = allLockupScripts.last
    val address3WithGroup = api.Address.from(lockupScript3)
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

  trait BuildGrouplessTransferTxWithEachGroupedAddressFixture extends P2PKFixture {
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
          UnlockScript.P2PK,
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
      verifyBalance(toAddress, result.unsignedTx, alphAmount, tokenAmount)
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

      val buildingGrouplessTransferTx = transferWithoutEnoughBalance(ALPH.alph(3), ALPH.alph(3))

      buildingGrouplessTransferTx.from is allLockupScripts(1)
      buildingGrouplessTransferTx.remainingAmounts._1 is ALPH.alphFromString("1.501 ALPH").get
      buildingGrouplessTransferTx.remainingAmounts._2 is AVector((tokenId, ALPH.alph(1)))
      buildingGrouplessTransferTx.remainingLockupScripts is AVector(
        allLockupScripts(2),
        allLockupScripts(0)
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

      val buildingGrouplessTransferTx = serverUtils
        .buildGrouplessTransferTxWithEachGroupedAddress(
          blockFlow,
          fromLockupScript,
          UnlockScript.P2PK,
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

        result.fundingTxs.value.length is 1
        verifyBalance(toAddress, result.unsignedTx, alphAmount, tokenAmount)
      }

      buildingGrouplessTransferTx.from is allLockupScripts(0)
      buildingGrouplessTransferTx.remainingLockupScripts is AVector(
        allLockupScripts(2),
        allLockupScripts(1)
      )
      verifyFinalResult(buildingGrouplessTransferTx)
    }
  }

  it should "sortedGroupedLockupScripts" in new P2PKFixture {
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
  trait GrouplessSweepFixture extends GrouplessFixture {
    def transfer(amount: U256, lockupScript: LockupScript.Asset): Unit = {
      val block =
        transfer(blockFlow, genesisPrivateKey, lockupScript, AVector.empty[(TokenId, U256)], amount)
      addAndCheck(blockFlow, block)
      if (!block.chainIndex.isIntraGroup) {
        addAndCheck(
          blockFlow,
          emptyBlock(blockFlow, ChainIndex(block.chainIndex.from, block.chainIndex.from))
        )
      }
    }
  }

  trait P2PKSweepFixture extends GrouplessSweepFixture {
    val (fromPrivateKey, fromPublicKey) = SecP256R1.generatePriPub()
    val publicKeyLike                   = PublicKeyLike.WebAuthn(fromPublicKey)
    val fromGroupIndex                  = groupIndexGen.sample.get
    val fromLockupScript                = LockupScript.p2pk(publicKeyLike, fromGroupIndex)
    val toGroupIndex                    = groupIndexGen.sample.get
    val toLockupScript = if (Random.nextBoolean()) {
      assetLockupGen(toGroupIndex).sample.get
    } else {
      LockupScript.P2PK(fromLockupScript.publicKey, toGroupIndex)
    }

    def buildSweepTxsAndSubmit(
        groupIndex: Option[GroupIndex],
        sweepAlphOnly: Option[Boolean] = None
    ): U256 = {
      val query = BuildSweepAddressTransactions(
        fromLockupScript.publicKey.rawBytes,
        Some(BuildTxCommon.GLWebAuthn),
        Address.Asset(toLockupScript),
        group = groupIndex,
        sweepAlphOnly = sweepAlphOnly
      )
      val unsignedTxs =
        serverUtils.buildSweepAddressTransactions(blockFlow, query).rightValue.unsignedTxs
      unsignedTxs.fold(U256.Zero) { case (acc, tx) =>
        val unsignedTx = deserialize[UnsignedTransaction](Hex.unsafe(tx.unsignedTx)).rightValue
        mineWithTx(signWithWebAuthn(unsignedTx, fromPrivateKey)._2)
        acc.addUnsafe(unsignedTx.gasFee)
      }
    }
  }

  trait P2PKSweepAlphOnlyFixture extends P2PKSweepFixture {
    private val tokenContract = "Contract SweepToken() { pub fn main() -> () {} }"
    private val tokenIssuance = Some(
      TokenIssuance.Info(Val.U256(U256.MaxValue), Some(LockupScript.p2pkh(genesisPublicKey)))
    )
    lazy val tokenId: TokenId = TokenId.from(
      createContract(tokenContract, tokenIssuanceInfo = tokenIssuance, chainIndex = chainIndex)._1
    )

    override val toGroupIndex: GroupIndex =
      groupIndexGen.retryUntil(_ != fromGroupIndex).sample.getOrElse(fromGroupIndex)
    override val toLockupScript: LockupScript.Asset =
      assetLockupGen(toGroupIndex).sample.getOrElse(
        LockupScript.P2PK(fromLockupScript.publicKey, toGroupIndex)
      )

    val tokenSweepAmount: U256 = ALPH.alph(5)

    val outputInfos = AVector(
      UnsignedTransaction.TxOutputInfo(
        fromLockupScript,
        dustUtxoAmount,
        AVector(tokenId -> tokenSweepAmount),
        None
      )
    )
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
  }

  it should "sweep from one group: P2PK" in new P2PKSweepFixture {
    (1 to 5).foreach(index => transfer(ALPH.alph(index.toLong), fromLockupScript))
    val totalAmount = ALPH.alph(15)
    checkBalance(blockFlow, fromLockupScript, totalAmount)

    val gasFee = buildSweepTxsAndSubmit(Some(fromGroupIndex))
    if (toLockupScript == fromLockupScript) {
      checkBalance(blockFlow, fromLockupScript, totalAmount.subUnsafe(gasFee))
    } else {
      checkBalance(blockFlow, fromLockupScript, U256.Zero)
      checkBalance(blockFlow, toLockupScript, totalAmount.subUnsafe(gasFee))
    }
  }

  it should "sweep from all groups: P2PK" in new P2PKSweepFixture {
    val allLockupScripts = brokerConfig.groupIndexes.map { groupIndex =>
      LockupScript.P2PK(fromLockupScript.publicKey, groupIndex)
    }
    allLockupScripts.foreach { lockupScript =>
      (1 to 5).foreach(index => transfer(ALPH.alph(index.toLong), lockupScript))
      checkBalance(blockFlow, lockupScript, ALPH.alph(15))
    }

    val totalAmount = ALPH.alph(15 * brokerConfig.groups.toLong)
    val gasFee      = buildSweepTxsAndSubmit(None)

    toLockupScript match {
      case p2pk: LockupScript.P2PK if p2pk.publicKey == fromLockupScript.publicKey =>
        allLockupScripts.filter(_ != p2pk).foreach { lockupScript =>
          checkBalance(blockFlow, lockupScript, U256.Zero)
        }
        checkBalance(blockFlow, p2pk, totalAmount.subUnsafe(gasFee))
      case _ =>
        allLockupScripts.foreach { lockupScript =>
          checkBalance(blockFlow, lockupScript, U256.Zero)
        }
        checkBalance(blockFlow, toLockupScript, totalAmount.subUnsafe(gasFee))
    }
  }

  it should "sweep from one group with ALPH only: P2PK" in new P2PKSweepAlphOnlyFixture {
    (1 to 5).foreach(index => transfer(ALPH.alph(index.toLong), fromLockupScript))

    val initialFromBalance = blockFlow.getBalance(fromLockupScript, Int.MaxValue, false).rightValue
    val initialFromTokenAmount = initialFromBalance.totalTokens
      .find(_._1 == tokenId)
      .map(_._2)
      .getOrElse(U256.Zero)

    initialFromTokenAmount is tokenSweepAmount

    val gasFee = buildSweepTxsAndSubmit(Some(fromGroupIndex), sweepAlphOnly = Some(true))

    val finalFromBalance = blockFlow.getBalance(fromLockupScript, Int.MaxValue, false).rightValue
    val finalToBalance   = blockFlow.getBalance(toLockupScript, Int.MaxValue, false).rightValue

    finalFromBalance.totalTokens
      .find(_._1 == tokenId)
      .map(_._2)
      .getOrElse(U256.Zero) is initialFromTokenAmount
    finalFromBalance.totalAlph is dustUtxoAmount

    finalToBalance.totalTokens.find(_._1 == tokenId).map(_._2).getOrElse(U256.Zero) is U256.Zero
    finalToBalance.totalAlph is initialFromBalance.totalAlph
      .subUnsafe(gasFee)
      .subUnsafe(dustUtxoAmount)
  }

  trait P2HMPKSweepFixture extends P2HMPKFixture with GrouplessSweepFixture {
    val toGroupIndex = groupIndexGen.sample.get
    val toLockupScript = if (Random.nextBoolean()) {
      assetLockupGen(toGroupIndex).sample.get
    } else {
      LockupScript.P2HMPK(fromLockupScript.p2hmpkHash, toGroupIndex)
    }

    def buildSweepTxsAndSubmit(groupIndex: Option[GroupIndex]): U256 = {
      val query = BuildSweepMultisig(
        fromAddress = api.Address.from(fromLockupScript),
        fromPublicKeys = AVector(fromPublicKey0.bytes, fromPublicKey1.bytes, fromPublicKey2.bytes),
        fromPublicKeyTypes = Some(
          AVector(BuildTxCommon.GLWebAuthn, BuildTxCommon.GLSecP256K1, BuildTxCommon.GLED25519)
        ),
        fromPublicKeyIndexes = Some(AVector(0, 1)),
        Address.Asset(toLockupScript),
        group = groupIndex,
        multiSigType = Some(MultiSigType.P2HMPK)
      )
      val unsignedTxs = serverUtils.buildSweepMultisig(blockFlow, query).rightValue.unsignedTxs
      unsignedTxs.fold(U256.Zero) { case (acc, tx) =>
        val unsignedTx = deserialize[UnsignedTransaction](Hex.unsafe(tx.unsignedTx)).rightValue
        mineWithTx(signTx(unsignedTx))
        acc.addUnsafe(unsignedTx.gasFee)
      }
    }
  }

  it should "sweep from one group: P2HMPK" in new P2HMPKSweepFixture {
    (1 to 5).foreach(index => transfer(ALPH.alph(index.toLong), fromLockupScript))
    val totalAmount = ALPH.alph(15)
    checkBalance(blockFlow, fromLockupScript, totalAmount)

    val gasFee = buildSweepTxsAndSubmit(Some(chainIndex.from))
    if (toLockupScript == fromLockupScript) {
      checkBalance(blockFlow, fromLockupScript, totalAmount.subUnsafe(gasFee))
    } else {
      checkBalance(blockFlow, fromLockupScript, U256.Zero)
      checkBalance(blockFlow, toLockupScript, totalAmount.subUnsafe(gasFee))
    }
  }

  it should "sweep from all groups: P2HMPK" in new P2HMPKSweepFixture {
    allLockupScripts.foreach { lockupScript =>
      (1 to 5).foreach(index => transfer(ALPH.alph(index.toLong), lockupScript))
      checkBalance(blockFlow, lockupScript, ALPH.alph(15))
    }

    val totalAmount = ALPH.alph(15 * brokerConfig.groups.toLong)
    val gasFee      = buildSweepTxsAndSubmit(None)

    toLockupScript match {
      case p2hmpk: LockupScript.P2HMPK if p2hmpk.p2hmpkHash == fromLockupScript.p2hmpkHash =>
        allLockupScripts.filter(_ != p2hmpk).foreach { lockupScript =>
          checkBalance(blockFlow, lockupScript, U256.Zero)
        }
        checkBalance(blockFlow, p2hmpk, totalAmount.subUnsafe(gasFee))
      case _ =>
        allLockupScripts.foreach { lockupScript =>
          checkBalance(blockFlow, lockupScript, U256.Zero)
        }
        checkBalance(blockFlow, toLockupScript, totalAmount.subUnsafe(gasFee))
    }
  }

  it should "return the correct error message if building the transfer tx fails" in new P2PKFixture {
    val outputs = AVector.fill(200)(
      UnsignedTransaction.TxOutputInfo(baseLockupScript, ALPH.cent(1), AVector.empty, None)
    )
    (0 until 3).foreach { _ =>
      val unsignedTx = blockFlow
        .transfer(
          genesisPublicKey,
          outputs,
          None,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          ExtraUtxosInfo.empty
        )
        .rightValue
        .rightValue
      val tx    = Transaction.from(unsignedTx, genesisPrivateKey)
      val block = mineWithTxs(blockFlow, tx.chainIndex)((_, _) => AVector(tx))
      addAndCheck(blockFlow, block)
    }
    val balance =
      blockFlow.getUsableUtxos(baseLockupScript, Int.MaxValue).rightValue.sumBy(_.output.amount.v)
    balance is ALPH.alph(6).v

    val toLockupScript = assetLockupGen(groupIndexGen.sample.get).sample.get
    val destinations =
      AVector(Destination(Address.Asset(toLockupScript), Some(Amount(ALPH.alph(3)))))
    failTransfer(
      destinations,
      None,
      "Too many inputs for the transfer, consider to reduce the amount to send, or use the `sweep-address` endpoint to consolidate the inputs first"
    )
  }
}
