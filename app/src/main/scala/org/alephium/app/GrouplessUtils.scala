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

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString

import org.alephium.api.{model => api}
import org.alephium.api.{badRequest, failed, failedInIO, wrapResult, Try}
import org.alephium.api.model._
import org.alephium.crypto.{ED25519PublicKey, SecP256K1PublicKey, SecP256R1PublicKey}
import org.alephium.flow.core.{BlockFlow, ExtraUtxosInfo}
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.io.IOResult
import org.alephium.protocol.model
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.{TotalAmountNeeded, TxOutputInfo}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, PublicKeyLike, UnlockScript}
import org.alephium.util.{AVector, Hex, U256}

trait GrouplessUtils extends ChainedTxUtils { self: ServerUtils =>
  import GrouplessUtils._

  def getGrouplessBalance(
      blockFlow: BlockFlow,
      halfDecodedLockupScript: api.Address.HalfDecodedLockupScript,
      getMempoolUtxos: Boolean
  ): Try[Balance] = {
    for {
      allBalances <- wrapResult(AVector.from(brokerConfig.groupIndexes).mapE { groupIndex =>
        val lockupScript = halfDecodedLockupScript.getLockupScript(groupIndex)
        blockFlow.getBalance(lockupScript, apiConfig.defaultUtxosLimit, getMempoolUtxos)
      })
      balance <- allBalances.foldE(model.Balance.zero)(_ merge _).left.map(failed)
    } yield Balance.from(balance)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def allGroupedLockupScripts(
      lockup: LockupScript.GroupedAsset
  ): AVector[LockupScript.GroupedAsset] = {
    lockup match {
      case lockup: LockupScript.P2PK =>
        self.brokerConfig.groupIndexes
          .map(groupIndex => LockupScript.P2PK(lockup.publicKey, groupIndex))
          .asInstanceOf[AVector[LockupScript.GroupedAsset]]
      case lockup: LockupScript.P2HMPK =>
        self.brokerConfig.groupIndexes
          .map(groupIndex => LockupScript.P2HMPK(lockup.p2hmpkHash, groupIndex))
          .asInstanceOf[AVector[LockupScript.GroupedAsset]]
    }
  }

  protected def otherGroupsLockupScripts(
      lockupScript: LockupScript.GroupedAsset
  ): AVector[LockupScript.GroupedAsset] = {
    allGroupedLockupScripts(lockupScript).filter(_ != lockupScript)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def otherGroupsLockupPairs(
      lockupScript: LockupScript.P2PK
  ): AVector[(LockupScript.Asset, UnlockScript)] = {
    otherGroupsLockupScripts(lockupScript).map(lockup =>
      (lockup.asInstanceOf[LockupScript.Asset], UnlockScript.P2PK.asInstanceOf[UnlockScript])
    )
  }

  type TryBuildGrouplessTransferTx =
    Try[Either[AVector[BuildingGrouplessTransferTx], BuildGrouplessTransferTxResult]]

  // When building groupless transfer, because we do not know each grouped address's balance,
  // we could end up building more txs than needed.
  //
  // Here we use the breadth-first search to build the txs. First we try to build the tx with
  // single grouped address, then 2, 3 and 4 until we either succeed or run out of balance.
  //
  // Before the breadth-first search, we sort the grouped addresses by the amount of first
  // token (if exists) and ALPH that we want to transfer. The hope is that this could make us
  // find the right set of grouped addresses quicker.
  //
  // Before the breadth-first search, we also do a preliminary check to see if the balance is
  // enough, if not enough, we reject directly.

  def buildP2PKTransferTx(
      blockFlow: BlockFlow,
      query: BuildTransferTx,
      lockupScript: LockupScript.P2PK,
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[BuildGrouplessTransferTxResult] = {
    if (query.group.isEmpty) {
      buildGrouplessTransferTxWithoutExplicitGroup(
        blockFlow,
        lockupScript,
        UnlockScript.P2PK,
        query.destinations,
        query.gasAmount,
        query.gasPrice,
        query.targetBlockHash
      )
    } else {
      for {
        unsignedTx <- buildTransferUnsignedTransaction(blockFlow, query, extraUtxosInfo)
        result <- BuildGrouplessTransferTxResult.from(
          AVector(BuildSimpleTransferTxResult.from(unsignedTx))
        )
      } yield result
    }
  }

  def buildP2HMPKTransferTx(
      blockFlow: BlockFlow,
      query: BuildMultisig
  ): Try[BuildGrouplessTransferTxResult] = {
    for {
      pubKeys <- buildPublicKeyLikes(query.fromPublicKeys, query.fromPublicKeyTypes).left
        .map(badRequest)
      keyIndexes <- query.getP2HMPKKeyIndexes()
      result <- query.group match {
        case Some(group) =>
          for {
            lockPair <- buildP2PKHMPKLockPairWithGroup(pubKeys, keyIndexes, group)
            unsignedTx <- prepareUnsignedTransaction(
              blockFlow,
              lockPair._1,
              lockPair._2,
              query.destinations,
              query.gas,
              query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
              None,
              ExtraUtxosInfo.empty
            )
            result <- BuildGrouplessTransferTxResult.from(
              AVector(BuildSimpleTransferTxResult.from(unsignedTx))
            )
          } yield result
        case None =>
          for {
            lockPair <- buildP2PKHMPKLockPairWithDefaultGroup(pubKeys, keyIndexes)
            result <- buildGrouplessTransferTxWithoutExplicitGroup(
              blockFlow,
              lockPair._1,
              lockPair._2,
              query.destinations,
              query.gas,
              query.gasPrice,
              None
            )
          } yield result
      }
    } yield result
  }

  // scalastyle:off method.length
  def buildGrouplessTransferTxWithoutExplicitGroup(
      blockFlow: BlockFlow,
      lockupScript: LockupScript.GroupedAsset,
      unlockScript: UnlockScript,
      destinations: AVector[Destination],
      gasAmountOpt: Option[GasBox],
      gasPriceOpt: Option[GasPrice],
      targetBlockHashOpt: Option[BlockHash]
  ): Try[BuildGrouplessTransferTxResult] = {
    val outputInfos = prepareOutputInfos(destinations)
    val gasPrice    = gasPriceOpt.getOrElse(nonCoinbaseMinGasPrice)
    for {
      totalAmountNeeded <- blockFlow
        .checkAndCalcTotalAmountNeeded(
          lockupScript,
          outputInfos,
          gasAmountOpt,
          gasPrice
        )
        .left
        .map(badRequest)
      buildResult0 <- buildGrouplessTransferTxWithEachGroupedAddress(
        blockFlow,
        lockupScript,
        unlockScript,
        outputInfos,
        totalAmountNeeded,
        gasPrice,
        targetBlockHashOpt
      )
      buildResult1 <- buildResult0 match {
        case Right(finalResult) =>
          Right(Right(finalResult))
        case Left(buildingTx) =>
          tryBuildGrouplessTransferTx(
            blockFlow,
            gasPrice,
            targetBlockHashOpt,
            outputInfos,
            totalAmountNeeded,
            AVector(buildingTx)
          )
      }
      buildResult <- buildResult1 match {
        case Right(result) =>
          Right(result)
        case Left(buildingTxsInProgress) =>
          val buildingTxInProgress = buildingTxsInProgress.sortBy(_.remainingAmounts._1).head
          val remmainingAlph       = buildingTxInProgress.remainingAmounts._1
          val remainingTokens      = buildingTxInProgress.remainingAmounts._2
          Left(failed(notEnoughBalanceError(remmainingAlph, remainingTokens)))
      }
    } yield {
      buildResult
    }
  }
  // scalastyle:on method.length

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def tryBuildGrouplessTransferTx(
      blockFlow: BlockFlow,
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash],
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      currentBuildingGrouplessTransferTxs: AVector[BuildingGrouplessTransferTx]
  ): TryBuildGrouplessTransferTx = {
    val currentBuildingGrouplessTransferTxsLength = currentBuildingGrouplessTransferTxs.length
    val nextBuildingGrouplessTransferTxs          = ArrayBuffer.empty[BuildingGrouplessTransferTx]

    def rec(index: Int): TryBuildGrouplessTransferTx = {
      if (index == currentBuildingGrouplessTransferTxsLength) {
        if (nextBuildingGrouplessTransferTxs.isEmpty) {
          Right(Left(currentBuildingGrouplessTransferTxs))
        } else {
          tryBuildGrouplessTransferTx(
            blockFlow,
            gasPrice,
            targetBlockHash,
            outputInfos,
            totalAmountNeeded,
            AVector.from(nextBuildingGrouplessTransferTxs)
          )
        }
      } else {
        val currentBuildingGrouplessTransferTx = currentBuildingGrouplessTransferTxs(index)
        tryBuildGrouplessTransferTxFromSingleGroupedAddress(
          blockFlow,
          gasPrice,
          targetBlockHash,
          outputInfos,
          totalAmountNeeded,
          currentBuildingGrouplessTransferTx
        ) match {
          case Right(Right(result)) =>
            Right(Right(result))
          case Right(Left(buildingGrouplessTransferTxs)) =>
            nextBuildingGrouplessTransferTxs ++= buildingGrouplessTransferTxs
            rec(index + 1)
          case Left(error) =>
            Left(error)
        }
      }
    }

    rec(0)
  }

  // scalastyle:off method.length
  def tryBuildGrouplessTransferTxFromSingleGroupedAddress(
      blockFlow: BlockFlow,
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash],
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      currentBuildingGrouplessTx: BuildingGrouplessTransferTx
  ): TryBuildGrouplessTransferTx = {
    val buildingGrouplessTransferTxs = ArrayBuffer.empty[BuildingGrouplessTransferTx]
    val remainingLockupScriptsLength = currentBuildingGrouplessTx.remainingLockupScripts.length

    @tailrec
    def rec(index: Int): TryBuildGrouplessTransferTx = {
      if (index == remainingLockupScriptsLength) {
        Right(Left(AVector.from(buildingGrouplessTransferTxs)))
      } else {
        val selectedLockupScript = currentBuildingGrouplessTx.remainingLockupScripts(index)
        val remainingAmounts     = currentBuildingGrouplessTx.remainingAmounts

        transferFromFallbackAddress(
          blockFlow,
          (selectedLockupScript, currentBuildingGrouplessTx.fromUnlockScript),
          currentBuildingGrouplessTx.from,
          remainingAmounts._1,
          remainingAmounts._2,
          gasPrice,
          targetBlockHash
        ) match {
          case Left(_) =>
            buildingGrouplessTransferTxs += currentBuildingGrouplessTx.copy(
              remainingLockupScripts =
                currentBuildingGrouplessTx.remainingLockupScripts.drop(index + 1)
            )
            rec(index + 1)
          case Right((unsignedTx, newRemainingAlph, newRemainingTokens)) =>
            if (newRemainingAlph.isZero && newRemainingTokens.isEmpty) {
              val crossGroupTxs = currentBuildingGrouplessTx.builtUnsignedTxsSoFar :+ unsignedTx
              blockFlow
                .transfer(
                  currentBuildingGrouplessTx.from,
                  currentBuildingGrouplessTx.fromUnlockScript,
                  outputInfos,
                  totalAmountNeeded,
                  currentBuildingGrouplessTx.utxos ++ getExtraUtxos(
                    currentBuildingGrouplessTx.from,
                    crossGroupTxs
                  ),
                  None,
                  gasPrice
                )
                .left
                .map(failed)
                .flatMap { unsignedTx =>
                  val allUnsignedTxs = crossGroupTxs :+ unsignedTx
                  BuildGrouplessTransferTxResult
                    .from(allUnsignedTxs.map(BuildSimpleTransferTxResult.from))
                    .map(Right(_))
                }
            } else {
              val remainingLockupScripts =
                currentBuildingGrouplessTx.remainingLockupScripts.drop(index + 1)
              buildingGrouplessTransferTxs += currentBuildingGrouplessTx.copy(
                builtUnsignedTxsSoFar =
                  currentBuildingGrouplessTx.builtUnsignedTxsSoFar :+ unsignedTx,
                remainingLockupScripts = remainingLockupScripts,
                remainingAmounts = (newRemainingAlph, newRemainingTokens)
              )
              rec(index + 1)
            }
        }
      }
    }

    if (remainingLockupScriptsLength == 0) {
      Right(Left(AVector.empty))
    } else {
      rec(0)
    }
  }

  def buildGrouplessTransferTxWithEachGroupedAddress(
      blockFlow: BlockFlow,
      lockupScript: LockupScript.GroupedAsset,
      unlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash]
  ): Try[Either[BuildingGrouplessTransferTx, BuildGrouplessTransferTxResult]] = {
    val allLockupScripts = allGroupedLockupScripts(lockupScript)

    sortedGroupedLockupScripts(
      blockFlow,
      allLockupScripts,
      totalAmountNeeded,
      targetBlockHashOpt
    ) match {
      case Right(sortedScripts) =>
        for {
          result <- buildGrouplessTransferTxWithEachSortedGroupedAddress(
            blockFlow,
            sortedScripts.map(script => (script._1, script._2)),
            unlockScript,
            outputInfos,
            totalAmountNeeded,
            gasPrice
          )
        } yield result
      case Left(error) =>
        Left(failedInIO(error))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def buildGrouplessTransferTxWithEachSortedGroupedAddress(
      blockFlow: BlockFlow,
      allLockupScriptsWithUtxos: AVector[(LockupScript.GroupedAsset, AVector[AssetOutputInfo])],
      unlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      gasPrice: GasPrice
  ): Try[Either[BuildingGrouplessTransferTx, BuildGrouplessTransferTxResult]] = {
    val allLockupScriptsLength       = allLockupScriptsWithUtxos.length
    val buildingGrouplessTransferTxs = ArrayBuffer.empty[BuildingGrouplessTransferTx]

    @tailrec
    def rec(
        index: Int
    ): Try[Either[BuildingGrouplessTransferTx, BuildGrouplessTransferTxResult]] = {
      if (index == allLockupScriptsLength) {
        Right(Left(buildingGrouplessTransferTxs.head))
      } else {
        val (lockup, utxos) = allLockupScriptsWithUtxos(index)
        blockFlow.transfer(
          lockup,
          unlockScript,
          outputInfos,
          totalAmountNeeded,
          utxos,
          None,
          gasPrice
        ) match {
          case Right(unsignedTx) =>
            val tx = BuildSimpleTransferTxResult.from(unsignedTx)
            BuildGrouplessTransferTxResult.from(AVector(tx)).map(Right(_))

          case Left(_) =>
            val (alphBalance, tokenBalances) = getAvailableBalances(utxos)
            val maxGasFee                    = gasPrice * getMaximalGasPerTx()
            val remainAlph = maxGasFee
              .addUnsafe(totalAmountNeeded.alphAmount)
              .sub(alphBalance)
              .getOrElse(U256.Zero)
            val (remainTokens, _) = calcTokenAmount(tokenBalances, totalAmountNeeded.tokens)

            buildingGrouplessTransferTxs += BuildingGrouplessTransferTx(
              lockup,
              unlockScript,
              utxos,
              allLockupScriptsWithUtxos.remove(index).map(_._1),
              (remainAlph, remainTokens),
              AVector.empty
            )
            rec(index + 1)
        }
      }
    }

    rec(0)
  }
  // scalastyle:on method.length

  private def notEnoughBalanceError(
      alphBalance: U256,
      tokenBalances: AVector[(TokenId, U256)]
  ): String = {
    val notEnoughAssets = ArrayBuffer.empty[String]

    if (!alphBalance.isZero) {
      notEnoughAssets += Amount.toAlphString(alphBalance)
    }

    for ((tokenId, amount) <- tokenBalances) {
      if (!amount.isZero) {
        notEnoughAssets += s"${tokenId.toHexString}: $amount"
      }
    }

    if (notEnoughAssets.isEmpty) {
      "Not enough balance"
    } else {
      s"Not enough balance: ${notEnoughAssets.mkString(", ")}"
    }
  }

  type GrouplessAssetInfo =
    AVector[(LockupScript.GroupedAsset, AVector[AssetOutputInfo], (U256, AVector[(TokenId, U256)]))]
  def sortedGroupedLockupScripts(
      blockFlow: BlockFlow,
      allGroupedLockupScripts: AVector[LockupScript.GroupedAsset],
      totalAmountNeeded: TotalAmountNeeded,
      targetBlockHashOpt: Option[BlockHash]
  ): IOResult[GrouplessAssetInfo] = {
    allGroupedLockupScripts
      .mapE { lockup =>
        blockFlow
          .getUsableUtxos(
            targetBlockHashOpt,
            lockup,
            self.apiConfig.defaultUtxosLimit
          )
          .map { utxos =>
            val (alphBalance, tokenBalances) = getAvailableBalances(utxos)
            (lockup, utxos, (alphBalance, tokenBalances))
          }
      }
      .map(_.sortBy(_._3)(orderingBasedOnTotalAmount(totalAmountNeeded)))
  }

  // Sort based on the first token (if exists) and then ALPH. The idea is that if we are sending
  // a token, we might want to start with addresses with most of that token. If we are sending ALPH,
  // we might want to start with addresses with most ALPH.
  // The case where we are sending multiple tokens are not considered.
  private def orderingBasedOnTotalAmount(
      totalAmountNeeded: UnsignedTransaction.TotalAmountNeeded
  ): Ordering[(U256, AVector[(TokenId, U256)])] = {
    val ordering: Ordering[(U256, AVector[(TokenId, U256)])] =
      if (totalAmountNeeded.tokens.isEmpty) {
        Ordering.by { case (alphBalance, _) => alphBalance }
      } else {
        val firstTokenId = totalAmountNeeded.tokens.head._1
        Ordering.by { case (alphBalance, tokenBalances) =>
          val tokenAmount = tokenBalances.find(_._1 == firstTokenId).map(_._2).getOrElse(U256.Zero)
          (tokenAmount, alphBalance)
        }
      }

    ordering.reverse
  }

  private def sweepFromAllGroups(
      blockFlow: BlockFlow,
      fromLockPair: (LockupScript.GroupedAsset, UnlockScript),
      query: BuildSweepCommon
  ): Try[AVector[UnsignedTransaction]] = {
    val (lockupScript, unlockScript) = fromLockPair
    allGroupedLockupScripts(lockupScript).foldE(AVector.empty[UnsignedTransaction]) {
      case (acc, fromLockupScript) =>
        prepareSweepAddressTransaction(blockFlow, (fromLockupScript, unlockScript), query) match {
          case Right(txs)  => Right(acc ++ txs)
          case Left(error) => Left(error)
        }
    }
  }

  def prepareGrouplessSweepAddressTransaction(
      blockFlow: BlockFlow,
      fromLockPair: (LockupScript.GroupedAsset, UnlockScript),
      query: BuildSweepCommon
  ): Try[AVector[UnsignedTransaction]] = {
    if (query.group.isDefined) {
      // sweep from one group
      prepareSweepAddressTransaction(blockFlow, fromLockPair, query)
    } else {
      // sweep from all groups
      sweepFromAllGroups(blockFlow, fromLockPair, query)
    }
  }

  private def buildP2PKHMPKLockPairWithGroup(
      pubKeys: AVector[PublicKeyLike],
      keyIndexes: AVector[Int],
      groupIndex: GroupIndex
  ): Try[(LockupScript.P2HMPK, UnlockScript.P2HMPK)] = {
    LockupScript
      .P2HMPK(pubKeys, keyIndexes.length, groupIndex)
      .left
      .map(badRequest)
      .flatMap(buildP2PKHMPKLockPair(pubKeys, keyIndexes, _))
  }

  private def buildP2PKHMPKLockPairWithDefaultGroup(
      pubKeys: AVector[PublicKeyLike],
      keyIndexes: AVector[Int]
  ): Try[(LockupScript.P2HMPK, UnlockScript.P2HMPK)] = {
    LockupScript
      .P2HMPK(pubKeys, keyIndexes.length)
      .left
      .map(badRequest)
      .flatMap(buildP2PKHMPKLockPair(pubKeys, keyIndexes, _))
  }

  private def buildP2PKHMPKLockPair(
      pubKeys: AVector[PublicKeyLike],
      keyIndexes: AVector[Int],
      lockupScript: LockupScript.P2HMPK
  ): Try[(LockupScript.P2HMPK, UnlockScript.P2HMPK)] = {
    val unlockScript = UnlockScript.P2HMPK(pubKeys, keyIndexes)
    UnlockScript.P2HMPK.validate(unlockScript).left.map(badRequest).map { _ =>
      (lockupScript, unlockScript)
    }
  }

  def buildP2HMPKSweepMultisig(
      blockFlow: BlockFlow,
      query: BuildSweepMultisig
  ): Try[BuildSweepAddressTransactionsResult] = {
    for {
      fromAddress <- query.getFromAddress()
      pubKeys <- buildPublicKeyLikes(query.fromPublicKeys, query.fromPublicKeyTypes).left
        .map(badRequest)
      keyIndexes <- query.getP2HMPKKeyIndexes()
      lockPair <- query.group match {
        case Some(group) => buildP2PKHMPKLockPairWithGroup(pubKeys, keyIndexes, group)
        case None        => buildP2PKHMPKLockPairWithDefaultGroup(pubKeys, keyIndexes)
      }
      unsignedTxs <- prepareGrouplessSweepAddressTransaction(blockFlow, lockPair, query)
    } yield {
      BuildSweepAddressTransactionsResult.from(
        unsignedTxs,
        fromAddress.groupIndex,
        query.toAddress.groupIndex
      )
    }
  }
}

object GrouplessUtils {
  final case class BuildingGrouplessTransferTx(
      from: LockupScript.GroupedAsset,
      fromUnlockScript: UnlockScript,
      utxos: AVector[AssetOutputInfo],
      remainingLockupScripts: AVector[LockupScript.GroupedAsset],
      remainingAmounts: (U256, AVector[(TokenId, U256)]),
      builtUnsignedTxsSoFar: AVector[UnsignedTransaction]
  )

  def buildPublicKeyLikes(
      rawKeys: AVector[ByteString],
      keyTypesOpt: Option[AVector[BuildTxCommon.PublicKeyType]]
  ): Either[String, AVector[PublicKeyLike]] = {
    for {
      _          <- checkPublicKeys(rawKeys)
      keyTypes   <- checkPublicKeyTypes(rawKeys, keyTypesOpt)
      publicKeys <- convertPublicKeys(rawKeys, keyTypes)
    } yield publicKeys
  }

  private def checkPublicKeys(rawKeys: AVector[ByteString]): Either[String, Unit] = {
    if (rawKeys.length == 0) {
      Left("`keys` can not be empty")
    } else {
      Right(())
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def checkPublicKeyTypes(
      rawKeys: AVector[ByteString],
      keyTypesOpt: Option[AVector[BuildTxCommon.PublicKeyType]]
  ): Either[String, AVector[BuildTxCommon.PublicKeyType]] = {
    if (keyTypesOpt.isDefined) {
      if (keyTypesOpt.get.length != rawKeys.length) {
        Left("`keyTypes` length should be the same as `keys` length")
      } else {
        Right(keyTypesOpt.get)
      }
    } else {
      Right(AVector.fill(rawKeys.length)(BuildTxCommon.Default))
    }
  }

  private def convertPublicKeys(
      rawKeys: AVector[ByteString],
      keyTypes: AVector[BuildTxCommon.PublicKeyType]
  ): Either[String, AVector[PublicKeyLike]] = {
    keyTypes.foldWithIndexE(AVector.empty[PublicKeyLike]) { (publicKeys, keyType, index) =>
      val rawKey = rawKeys(index)
      val publicKey = keyType match {
        case BuildTxCommon.Default =>
          SecP256K1PublicKey.from(rawKey).map(PublicKeyLike.SecP256K1.apply)
        case BuildTxCommon.GLED25519 =>
          ED25519PublicKey.from(rawKey).map(PublicKeyLike.ED25519.apply)
        case BuildTxCommon.GLSecP256K1 =>
          SecP256K1PublicKey.from(rawKey).map(PublicKeyLike.SecP256K1.apply)
        case BuildTxCommon.GLSecP256R1 =>
          SecP256R1PublicKey.from(rawKey).map(PublicKeyLike.SecP256R1.apply)
        case BuildTxCommon.GLWebAuthn =>
          SecP256R1PublicKey.from(rawKey).map(PublicKeyLike.WebAuthn.apply)
        case BuildTxCommon.BIP340Schnorr =>
          None // BIP340Schnorr not supported
      }

      publicKey match {
        case Some(publicKey) =>
          Right(publicKeys :+ publicKey)
        case None =>
          Left(s"Invalid public key ${Hex.toHexString(rawKey)} for keyType ${keyType.name}")
      }
    }
  }
}
