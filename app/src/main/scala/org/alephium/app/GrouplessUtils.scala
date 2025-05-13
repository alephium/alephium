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

import org.alephium.api.{badRequest, failed, failedInIO, wrapResult, Try}
import org.alephium.api.model._
import org.alephium.flow.core.{BlockFlow, ExtraUtxosInfo}
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.protocol.model
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.{TotalAmountNeeded, TxOutputInfo}
import org.alephium.protocol.vm.{GasPrice, LockupScript, UnlockScript}
import org.alephium.util.AVector
import org.alephium.util.U256
import org.alephium.io.IOResult

trait GrouplessUtils extends ChainedTxUtils { self: ServerUtils =>
  import GrouplessUtils.BuildingGrouplessTransferTx

  def getGrouplessBalance(
      blockFlow: BlockFlow,
      halfDecodedP2PK: LockupScript.HalfDecodedP2PK,
      getMempoolUtxos: Boolean
  ): Try[Balance] = {
    for {
      allBalances <- wrapResult(AVector.from(brokerConfig.groupRange).mapE { groupIndex =>
        val lockupScript =
          LockupScript.p2pk(halfDecodedP2PK.publicKey, GroupIndex.unsafe(groupIndex))
        blockFlow.getBalance(lockupScript, apiConfig.defaultUtxosLimit, getMempoolUtxos)
      })
      balance <- allBalances.foldE(model.Balance.zero)(_ merge _).left.map(failed)
    } yield Balance.from(balance)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def allGroupedLockupScripts(
      lockup: LockupScript.P2PK
  ): AVector[LockupScript.P2PK] = {
    AVector
      .from(self.brokerConfig.groupRange)
      .map(groupIndex => LockupScript.P2PK(lockup.publicKey, GroupIndex.unsafe(groupIndex)))
  }

  protected def otherGroupsLockupScripts(
      lockupScript: LockupScript.P2PK
  ): AVector[LockupScript.P2PK] = {
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

  def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      query: BuildTransferTx,
      lockupScript: LockupScript.P2PK,
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[BuildGrouplessTransferTxResult] = {
    if (query.group.isEmpty) {
      buildGrouplessTransferTx(blockFlow, query, lockupScript).flatMap {
        case Right(result) =>
          Right(result)
        case Left(buildingTxsInProgress) =>
          val buildingTxInProgress = buildingTxsInProgress.sortBy(_.remainingAmounts._1).head
          val remmainingAlph       = buildingTxInProgress.remainingAmounts._1
          val remainingTokens      = buildingTxInProgress.remainingAmounts._2
          Left(failed(notEnoughBalanceError(remmainingAlph, remainingTokens)))
      }
    } else {
      for {
        unsignedTx <- buildTransferUnsignedTransaction(blockFlow, query, extraUtxosInfo)
        result <- BuildGrouplessTransferTxResult.from(
          AVector(BuildSimpleTransferTxResult.from(unsignedTx))
        )
      } yield result
    }
  }

  def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      query: BuildTransferTx,
      lockupScript: LockupScript.P2PK
  ): TryBuildGrouplessTransferTx = {
    val outputInfos = prepareOutputInfos(query.destinations)
    val gasPrice    = query.gasPrice.getOrElse(nonCoinbaseMinGasPrice)
    for {
      totalAmountNeeded <- blockFlow
        .checkAndCalcTotalAmountNeeded(
          lockupScript,
          outputInfos,
          query.gasAmount,
          gasPrice
        )
        .left
        .map(badRequest)
      buildResult <- tryBuildGrouplessTransferTxWithSingleAddress(
        blockFlow,
        lockupScript,
        outputInfos,
        totalAmountNeeded,
        gasPrice,
        query.targetBlockHash
      )
      result <- buildResult match {
        case Right(finalResult) =>
          Right(Right(finalResult))
        case Left(buildingTxs) =>
          tryBuildGrouplessTransferTx(
            blockFlow,
            gasPrice,
            query.targetBlockHash,
            outputInfos,
            totalAmountNeeded,
            buildingTxs
          )
      }
    } yield {
      result
    }
  }

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
        tryBuildGrouplessTransferTx(
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
  def tryBuildGrouplessTransferTx(
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

  def tryBuildGrouplessTransferTxWithSingleAddress(
      blockFlow: BlockFlow,
      lockupScript: LockupScript.P2PK,
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
        tryBuildGrouplessTransferTxWithSingleAddress(
          blockFlow,
          sortedScripts.map(_._1),
          outputInfos,
          totalAmountNeeded,
          gasPrice,
          targetBlockHashOpt
        )
      case Left(error) =>
        Left(failedInIO(error))
    }
  }

  def tryBuildGrouplessTransferTxWithSingleAddress(
      blockFlow: BlockFlow,
      allLockupScripts: AVector[LockupScript.P2PK],
      outputInfos: AVector[TxOutputInfo],
      totalAmountNeeded: TotalAmountNeeded,
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash]
  ): TryBuildGrouplessTransferTx = {
    val allLockupScriptsLength       = allLockupScripts.length
    val buildingGrouplessTransferTxs = ArrayBuffer.empty[BuildingGrouplessTransferTx]

    @tailrec
    def rec(index: Int): TryBuildGrouplessTransferTx = {
      if (index == allLockupScriptsLength) {
        Right(Left(AVector.from(buildingGrouplessTransferTxs)))
      } else {
        val lockup = allLockupScripts(index)
        blockFlow.getUsableUtxos(
          targetBlockHashOpt,
          lockup,
          self.apiConfig.defaultUtxosLimit
        ) match {
          case Right(utxos) =>
            blockFlow.transfer(
              lockup,
              UnlockScript.P2PK,
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
                  allLockupScripts.remove(index),
                  (remainAlph, remainTokens),
                  AVector.empty
                )
                rec(index + 1)
            }
          case Left(error) =>
            Left(failedInIO(error))
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
      allGroupedLockupScripts: AVector[LockupScript.P2PK],
      totalAmountNeeded: TotalAmountNeeded,
      targetBlockHashOpt: Option[BlockHash]
  ): IOResult[
    AVector[(LockupScript.P2PK, AVector[AssetOutputInfo], (U256, AVector[(TokenId, U256)]))]
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
}

object GrouplessUtils {
  final case class BuildingGrouplessTransferTx(
      from: LockupScript.P2PK,
      utxos: AVector[AssetOutputInfo],
      remainingLockupScripts: AVector[LockupScript.P2PK],
      remainingAmounts: (U256, AVector[(TokenId, U256)]),
      builtUnsignedTxsSoFar: AVector[UnsignedTransaction]
  )
}
