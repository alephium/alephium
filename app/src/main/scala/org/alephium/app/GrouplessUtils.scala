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
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString

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
      halfDecodedLockupScript: LockupScript.HalfDecodedLockupScript,
      getMempoolUtxos: Boolean
  ): Try[Balance] = {
    for {
      allBalances <- wrapResult(AVector.from(brokerConfig.groupIndexes).mapE { groupIndex =>
        val lockupScript = halfDecodedLockupScript.toCompleteLockupScript(groupIndex).lockupScript
        blockFlow.getBalance(lockupScript, apiConfig.defaultUtxosLimit, getMempoolUtxos)
      })
      balance <- allBalances.foldE(model.Balance.zero)(_ merge _).left.map(failed)
    } yield Balance.from(balance)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def allGroupedLockupScripts(
      lockup: LockupScript.GrouplessAsset
  ): AVector[LockupScript.GrouplessAsset] = {
    lockup match {
      case lockup: LockupScript.P2PK =>
        self.brokerConfig.groupIndexes
          .map(groupIndex => LockupScript.P2PK(lockup.publicKey, groupIndex))
          .asInstanceOf[AVector[LockupScript.GrouplessAsset]]
      case lockup: LockupScript.P2HMPK =>
        self.brokerConfig.groupIndexes
          .map(groupIndex => LockupScript.P2HMPK(lockup.p2hmpkHash, groupIndex))
          .asInstanceOf[AVector[LockupScript.GrouplessAsset]]
    }
  }

  protected def otherGroupsLockupScripts(
      lockupScript: LockupScript.GrouplessAsset
  ): AVector[LockupScript.GrouplessAsset] = {
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
  // single groupled address, then 2, 3 and 4 until we either succeed or run out of balance.
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
    val publicKeyLikes = buildPublicKeyLikes(query.fromPublicKeys, query.fromPublicKeyTypes)
    publicKeyLikes.left.map(badRequest).flatMap { pubKeys =>
      (query.group, query.fromPublicKeyIndexes) match {
        case (_, None) =>
          Left(badRequest("fromPublicKeyIndexes is required for P2HMPK multisig"))
        case (Some(group), Some(keyIndexes)) =>
          for {
            unsignedTx <- prepareUnsignedTransaction(
              blockFlow,
              LockupScript.P2HMPK(pubKeys, keyIndexes.length, group),
              UnlockScript.P2HMPK(pubKeys, keyIndexes),
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
        case (None, Some(keyIndexes)) =>
          buildGrouplessTransferTxWithoutExplicitGroup(
            blockFlow,
            LockupScript.P2HMPK(pubKeys, keyIndexes.length),
            UnlockScript.P2HMPK(pubKeys, keyIndexes),
            query.destinations,
            query.gas,
            query.gasPrice,
            None
          )
      }
    }
  }

  // scalastyle:off method.length
  def buildGrouplessTransferTxWithoutExplicitGroup(
      blockFlow: BlockFlow,
      lockupScript: LockupScript.GrouplessAsset,
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
        case Left(buildingTxs) =>
          tryBuildGrouplessTransferTx(
            blockFlow,
            gasPrice,
            targetBlockHashOpt,
            outputInfos,
            totalAmountNeeded,
            buildingTxs
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
          (selectedLockupScript, UnlockScript.P2PK),
          currentBuildingGrouplessTx.from,
          remainingAmounts._1,
          remainingAmounts._2,
          gasPrice,
          targetBlockHash
        ) match {
          case Left(_) =>
            buildingGrouplessTransferTxs += currentBuildingGrouplessTx.copy(
              remainingLockupScripts =
                currentBuildingGrouplessTx.remainingLockupScripts.remove(index)
            )
            rec(index + 1)
          case Right((unsignedTx, newRemainingAlph, newRemainingTokens)) =>
            if (newRemainingAlph.isZero && newRemainingTokens.isEmpty) {
              val crossGroupTxs = currentBuildingGrouplessTx.builtUnsignedTxsSoFar :+ unsignedTx
              blockFlow
                .transfer(
                  currentBuildingGrouplessTx.from,
                  UnlockScript.P2PK,
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
              buildingGrouplessTransferTxs += currentBuildingGrouplessTx.copy(
                builtUnsignedTxsSoFar =
                  currentBuildingGrouplessTx.builtUnsignedTxsSoFar :+ unsignedTx,
                remainingLockupScripts =
                  currentBuildingGrouplessTx.remainingLockupScripts.remove(index),
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
      lockupScript: LockupScript.GrouplessAsset,
      unlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash]
  ): TryBuildGrouplessTransferTx = {
    val allLockupScripts = allGroupedLockupScripts(lockupScript)

    sortedGroupedLockupScripts(
      blockFlow,
      allLockupScripts,
      totalAmountNeeded,
      targetBlockHashOpt
    ) match {
      case Right(sortedScripts) =>
        for {
          _ <- checkEnoughBalance(totalAmountNeeded, sortedScripts.map(_._3))
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

  def buildGrouplessTransferTxWithEachSortedGroupedAddress(
      blockFlow: BlockFlow,
      allLockupScriptsWithUtxos: AVector[(LockupScript.GrouplessAsset, AVector[AssetOutputInfo])],
      unlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      gasPrice: GasPrice
  ): TryBuildGrouplessTransferTx = {
    val allLockupScriptsLength       = allLockupScriptsWithUtxos.length
    val buildingGrouplessTransferTxs = ArrayBuffer.empty[BuildingGrouplessTransferTx]

    @tailrec
    def rec(index: Int): TryBuildGrouplessTransferTx = {
      if (index == allLockupScriptsLength) {
        Right(Left(AVector.from(buildingGrouplessTransferTxs)))
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

  def sortedGroupedLockupScripts(
      blockFlow: BlockFlow,
      allGroupedLockupScripts: AVector[LockupScript.GrouplessAsset],
      totalAmountNeeded: TotalAmountNeeded,
      targetBlockHashOpt: Option[BlockHash]
  ): IOResult[
    AVector[
      (LockupScript.GrouplessAsset, AVector[AssetOutputInfo], (U256, AVector[(TokenId, U256)]))
    ]
  ] = {
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

  // Priliminary check to see if the balance is enough, if not enough reject directly
  // otherwise proceed to build the txs, which could still return not enough balance error
  // due to gas fee and dust amounts.
  def checkEnoughBalance(
      totalAmountNeeded: TotalAmountNeeded,
      balances: AVector[(U256, AVector[(TokenId, U256)])]
  ): Try[Unit] = {
    val totalAlphBalance   = balances.map(_._1).fold(U256.Zero)(_ addUnsafe _)
    val totalTokenBalances = mutable.Map.empty[TokenId, U256]
    for ((_, tokenBalances) <- balances) {
      for ((tokenId, amount) <- tokenBalances) {
        totalTokenBalances(tokenId) =
          totalTokenBalances.getOrElse(tokenId, U256.Zero).addUnsafe(amount)
      }
    }

    val missingAlph = totalAmountNeeded.alphAmount.sub(totalAlphBalance).getOrElse(U256.Zero)
    val missingTokens = totalAmountNeeded.tokens.collect { case (tokenId, amount) =>
      amount
        .sub(totalTokenBalances.getOrElse(tokenId, U256.Zero))
        .filter(_ > U256.Zero)
        .map(tokenId -> _)
    }

    if (missingAlph == U256.Zero && missingTokens.isEmpty) {
      Right(())
    } else {
      Left(failed(notEnoughBalanceError(missingAlph, missingTokens)))
    }
  }
}

object GrouplessUtils {
  final case class BuildingGrouplessTransferTx(
      from: LockupScript.GrouplessAsset,
      utxos: AVector[AssetOutputInfo],
      remainingLockupScripts: AVector[LockupScript.GrouplessAsset],
      remainingAmounts: (U256, AVector[(TokenId, U256)]),
      builtUnsignedTxsSoFar: AVector[UnsignedTransaction]
  )

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def buildPublicKeyLikes(
      rawKeys: AVector[ByteString],
      keyTypesOpt: Option[AVector[BuildTxCommon.PublicKeyType]]
  ): Either[String, AVector[PublicKeyLike]] = {
    val keyTypes: Either[String, AVector[BuildTxCommon.PublicKeyType]] =
      if (keyTypesOpt.isDefined) {
        if (keyTypesOpt.get.length != rawKeys.length) {
          Left("`keyTypes` length should be the same as `keys` length")
        } else {
          Right(keyTypesOpt.get)
        }
      } else {
        Right(AVector.fill(rawKeys.length)(BuildTxCommon.Default))
      }

    keyTypes.flatMap {
      _.foldWithIndexE(AVector.empty[PublicKeyLike]) { (publicKeys, keyType, index) =>
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
            Left(s"Invalid public key ${Hex.toHexString(rawKey)} for keyType $keyType")
        }
      }
    }
  }
}
