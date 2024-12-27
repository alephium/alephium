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

import sttp.model.StatusCode

import org.alephium.api.{badRequest, failed, failedInIO, wrapResult, ApiError, Try}
import org.alephium.api.model._
import org.alephium.flow.core.{BlockFlow, FlowUtils, TxUtils}
import org.alephium.flow.gasestimation.GasEstimationMultiplier
import org.alephium.protocol.ALPH
import org.alephium.protocol.model
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.TotalAmountNeeded
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript, UnlockScript}
import org.alephium.serde.deserialize
import org.alephium.util.{AVector, Math, TimeStamp, U256}

trait GrouplessUtils { self: ServerUtils =>
  def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessTransferTx
  ): Try[AVector[BuildTransferTxResult]] = {
    val outputInfos = prepareOutputInfos(query.destinations)
    val gasPrice    = query.gasPrice.getOrElse(nonCoinbaseMinGasPrice)
    for {
      lockPair <- query.lockPair.left.map(badRequest)
      totalAmount <- blockFlow
        .checkAndCalcTotalAmountNeeded(
          lockPair._1,
          outputInfos,
          None,
          gasPrice
        )
        .left
        .map(badRequest)
      utxos <- blockFlow
        .getUsableUtxos(query.targetBlockHash, lockPair._1, self.apiConfig.defaultUtxosLimit)
        .left
        .map(failedInIO)
      txs <- buildGrouplessTransferTx(
        blockFlow,
        lockPair._1,
        lockPair._2,
        totalAmount,
        utxos,
        outputInfos,
        gasPrice,
        query.targetBlockHash
      )
    } yield txs.map(BuildTransferTxResult.from)
  }

  private def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      lockup: LockupScript.P2PK,
      unlock: UnlockScript,
      totalAmount: UnsignedTransaction.TotalAmountNeeded,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      outputInfos: AVector[UnsignedTransaction.TxOutputInfo],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Try[AVector[UnsignedTransaction]] = {
    blockFlow.transfer(lockup, unlock, outputInfos, totalAmount, utxos, None, gasPrice) match {
      case Right(unsignedTx) => Right(AVector(unsignedTx))
      case Left(_) =>
        for {
          crossGroupTxs <-
            buildGrouplessTransferTxs(
              blockFlow,
              lockup,
              totalAmount,
              utxos,
              gasPrice,
              targetBlockHash
            )
          tx <- blockFlow
            .transfer(
              lockup,
              unlock,
              outputInfos,
              totalAmount,
              utxos ++ getExtraUtxos(lockup, crossGroupTxs._1),
              None,
              gasPrice
            )
            .left
            .map(err => notEnoughAlph(crossGroupTxs._2, badRequest(err)))
        } yield crossGroupTxs._1 :+ tx
    }
  }

  @inline private def notEnoughAlph(
      amount: U256,
      error: ApiError[_ <: StatusCode]
  ): ApiError[_ <: StatusCode] = {
    if (amount.nonZero) {
      badRequest(s"Not enough ALPH balance, requires an additional ${ALPH.prettifyAmount(amount)}")
    } else {
      error
    }
  }

  private def buildGrouplessTransferTxs(
      blockFlow: BlockFlow,
      lockup: LockupScript.P2PK,
      totalAmount: TotalAmountNeeded,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Try[(AVector[UnsignedTransaction], U256, AVector[(TokenId, U256)])] = {
    val (alphNeeded, tokenNeeded, _) = totalAmount
    val (alphBalance, tokenBalances) = getAvailableBalances(utxos)
    val maxGasFee                    = gasPrice * getMaximalGasPerTx()
    val remainAlph        = maxGasFee.addUnsafe(alphNeeded).sub(alphBalance).getOrElse(U256.Zero)
    val (remainTokens, _) = calcTokenAmount(tokenBalances, tokenNeeded)
    val result = transferFromOtherGroups(
      blockFlow,
      lockup,
      remainAlph,
      remainTokens,
      gasPrice,
      targetBlockHash
    )
    if (result._3.nonEmpty) {
      val message =
        result._3.map(token => s"${token._1.toHexString}: ${token._2}").mkString(",")
      Left(badRequest(s"Not enough token balances, requires additional $message"))
    } else {
      Right(result)
    }
  }

  private def getExtraUtxos(lockup: LockupScript.P2PK, txs: AVector[UnsignedTransaction]) = {
    val extraUtxos = mutable.ArrayBuffer.empty[FlowUtils.AssetOutputInfo]
    txs.foreach(tx =>
      tx.fixedOutputs.mapWithIndex { case (output, index) =>
        if (output.lockupScript == lockup) {
          val outputInfo = FlowUtils.AssetOutputInfo(
            AssetOutputRef.from(output, TxOutputRef.key(tx.id, index)),
            output,
            FlowUtils.MemPoolOutput
          )
          extraUtxos.addOne(outputInfo)
        }
      }
    )
    AVector.from(extraUtxos)
  }

  private def transferFromOtherGroups(
      blockFlow: BlockFlow,
      toLockupScript: LockupScript.P2PK,
      alphAmount: U256,
      tokenAmounts: AVector[(TokenId, U256)],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ) = {
    val toGroupIndex = toLockupScript.groupIndex
    AVector
      .from(self.brokerConfig.groupRange)
      .fold((AVector.empty[UnsignedTransaction], alphAmount, tokenAmounts)) {
        case ((txs, remainAlph, remainTokens), fromGroupIndex) =>
          if (remainAlph.isZero && remainTokens.isEmpty) {
            (txs, remainAlph, remainTokens)
          } else if (fromGroupIndex == toGroupIndex.value) {
            (txs, remainAlph, remainTokens)
          } else {
            transferFromOtherGroup(
              blockFlow,
              GroupIndex.unsafe(fromGroupIndex),
              toLockupScript,
              remainAlph,
              remainTokens,
              gasPrice,
              targetBlockHash
            ) match {
              case Right(result) => (txs :+ result._1, result._2, result._3)
              case Left(_)       => (txs, remainAlph, remainTokens)
            }
          }
      }
  }

  private def getAvailableBalances(utxos: AVector[FlowUtils.AssetOutputInfo]) = {
    var alphBalance   = U256.Zero
    val tokenBalances = new TxUtils.TokenBalances(mutable.Map.empty)
    val currentTs     = TimeStamp.now()
    utxos.foreach { utxo =>
      if (utxo.output.lockTime <= currentTs) {
        alphBalance.add(utxo.output.amount).foreach(alphBalance = _)
        utxo.output.tokens.foreach { case (tokenId, amount) =>
          tokenBalances.addToken(tokenId, amount)
        }
      }
    }
    (alphBalance, tokenBalances.getBalances())
  }

  private def transferFromOtherGroup(
      blockFlow: BlockFlow,
      fromGroupIndex: GroupIndex,
      toLockup: LockupScript.P2PK,
      alphAmount: U256,
      tokenAmounts: AVector[(TokenId, U256)],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ) = {
    val fromLockup = LockupScript.P2PK.from(toLockup.publicKey, Some(fromGroupIndex))
    blockFlow
      .getUsableUtxos(targetBlockHash, fromLockup, self.apiConfig.defaultUtxosLimit)
      .left
      .map(_.getMessage)
      .flatMap { utxos =>
        val (alphBalance, tokenBalances)   = getAvailableBalances(utxos)
        val (remainTokens, transferTokens) = calcTokenAmount(tokenBalances, tokenAmounts)
        val transferAlph = calcAlphAmount(alphBalance, alphAmount, transferTokens.length, gasPrice)
        val outputInfos = AVector(
          UnsignedTransaction.TxOutputInfo(toLockup, transferAlph, transferTokens, None)
        )
        val fromUnlock = UnlockScript.P2PK(fromLockup.publicKey.keyType)
        buildTransferTx(blockFlow, fromLockup, fromUnlock, utxos, outputInfos, gasPrice)
          .map { tx =>
            val transferredAlph =
              tx.fixedOutputs.filter(_.lockupScript == toLockup).fold(U256.Zero) {
                case (acc, output) => acc.addUnsafe(output.amount)
              }
            (tx, alphAmount.sub(transferredAlph).getOrElse(U256.Zero), remainTokens)
          }
      }
  }

  private def buildTransferTx(
      blockFlow: BlockFlow,
      lockup: LockupScript.P2PK,
      unlock: UnlockScript,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      outputInfos: AVector[UnsignedTransaction.TxOutputInfo],
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    for {
      totalAmount <- blockFlow.checkAndCalcTotalAmountNeeded(lockup, outputInfos, None, gasPrice)
      unsignedTx <- blockFlow.transfer(
        lockup,
        unlock,
        outputInfos,
        totalAmount,
        utxos,
        None,
        gasPrice
      )
    } yield unsignedTx
  }

  private def calcAlphAmount(
      alphBalance: U256,
      alphNeeded: U256,
      tokenSize: Int,
      gasPrice: GasPrice
  ) = {
    val tokenDustAmount = U256.unsafe(tokenSize).mulUnsafe(dustUtxoAmount)
    if (alphNeeded <= tokenDustAmount) {
      tokenDustAmount
    } else {
      val extraAmount = tokenDustAmount.addUnsafe(gasPrice * getMaximalGasPerTx())
      if (alphBalance > extraAmount) {
        val available    = alphBalance.subUnsafe(extraAmount)
        val remainNeeded = alphNeeded.subUnsafe(tokenDustAmount)
        Math.min(available, remainNeeded).addUnsafe(tokenDustAmount)
      } else {
        tokenDustAmount
      }
    }
  }

  private def calcTokenAmount(
      tokenBalances: AVector[(TokenId, U256)],
      tokenNeeded: AVector[(TokenId, U256)]
  ): (AVector[(TokenId, U256)], AVector[(TokenId, U256)]) = {
    tokenNeeded.fold((AVector.empty[(TokenId, U256)], AVector.empty[(TokenId, U256)])) {
      case ((remains, transfers), (tokenId, needed)) =>
        tokenBalances.find(_._1 == tokenId) match {
          case Some((_, tokenBalance)) =>
            if (tokenBalance >= needed) {
              (remains, transfers :+ (tokenId -> needed))
            } else {
              val remainAmount = needed.subUnsafe(tokenBalance)
              (remains :+ (tokenId -> remainAmount), transfers :+ (tokenId -> tokenBalance))
            }
          case None => (remains :+ (tokenId -> needed), transfers)
        }
    }
  }

  def buildGrouplessExecuteScriptTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessExecuteScriptTx
  ): Try[BuildGrouplessExecuteScriptTxResult] = {
    for {
      multiplier <- GasEstimationMultiplier.from(query.gasEstimationMultiplier).left.map(badRequest)
      amounts    <- query.getAmounts.left.map(badRequest)
      lockPair   <- query.getLockPair()
      script <- deserialize[StatefulScript](query.bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      utxos <- blockFlow
        .getUsableUtxos(lockPair._1, apiConfig.defaultUtxosLimit)
        .left
        .map(failedInIO)
      result <- buildGrouplessExecuteScriptTx(
        blockFlow,
        amounts,
        lockPair,
        script,
        utxos,
        multiplier,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash
      )
    } yield {
      val (transferTxs, executeScriptTx, outputs) = result
      BuildGrouplessExecuteScriptTxResult(
        transferTxs.map(BuildTransferTxResult.from),
        BuildExecuteScriptTxResult.from(executeScriptTx, outputs)
      )
    }
  }

  // scalastyle:off parameter.number
  private def buildGrouplessExecuteScriptTx(
      blockFlow: BlockFlow,
      amounts: BuildTxCommon.ScriptTxAmounts,
      lockPair: (LockupScript.P2PK, UnlockScript),
      script: StatefulScript,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      multiplier: Option[GasEstimationMultiplier],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Try[(AVector[UnsignedTransaction], UnsignedTransaction, AVector[Output])] = {
    buildExecuteScriptTx(
      blockFlow,
      amounts,
      lockPair,
      script,
      utxos,
      multiplier,
      gasOpt,
      Some(gasPrice)
    ) match {
      case Right((unsignedTx, outputs)) => Right((AVector.empty, unsignedTx, outputs))
      case Left(_) =>
        val totalAmount = (amounts.estimatedAlph, amounts.tokens, amounts.tokens.length + 1)
        for {
          crossGroupTxs <-
            buildGrouplessTransferTxs(
              blockFlow,
              lockPair._1,
              totalAmount,
              utxos,
              gasPrice,
              targetBlockHash
            )
          result <- buildExecuteScriptTx(
            blockFlow,
            amounts,
            lockPair,
            script,
            utxos ++ getExtraUtxos(lockPair._1, crossGroupTxs._1),
            multiplier,
            gasOpt,
            Some(gasPrice)
          ).left.map(notEnoughAlph(crossGroupTxs._2, _))
        } yield (crossGroupTxs._1, result._1, result._2)
    }
  }
  // scalastyle:on parameter.number

  def buildGrouplessDeployContractTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessDeployContractTx
  ): Try[BuildGrouplessDeployContractTxResult] = {
    for {
      amounts <- query.getAmounts.left.map(badRequest)
      (contractDeposit, scriptTxAmounts) = amounts
      lockPair <- query.getLockPair()
      script <- buildDeployContractTxScript(
        query,
        contractDeposit,
        scriptTxAmounts.tokens,
        lockPair._1
      )
      utxos <- blockFlow
        .getUsableUtxos(lockPair._1, apiConfig.defaultUtxosLimit)
        .left
        .map(failedInIO)
      result <- buildGrouplessExecuteScriptTx(
        blockFlow,
        scriptTxAmounts,
        lockPair,
        script,
        utxos,
        None,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash
      )
    } yield {
      val (transferTxs, deployContractTx, _) = result
      BuildGrouplessDeployContractTxResult(
        transferTxs.map(BuildTransferTxResult.from),
        BuildDeployContractTxResult.from(deployContractTx)
      )
    }
  }

  def getGrouplessBalance(
      blockFlow: BlockFlow,
      address: Address,
      getMempoolUtxos: Boolean
  ): Try[Balance] = {
    for {
      p2pk <- address.lockupScript match {
        case lock: LockupScript.P2PK => Right(lock)
        case _                       => Left(badRequest(s"Invalid groupless address: $address"))
      }
      allBalances <- wrapResult(AVector.from(brokerConfig.groupRange).mapE { groupIndex =>
        val lockupScript = LockupScript.p2pk(p2pk.publicKey, Some(GroupIndex.unsafe(groupIndex)))
        blockFlow.getBalance(lockupScript, apiConfig.defaultUtxosLimit, getMempoolUtxos)
      })
      balance <- allBalances.foldE(model.Balance.zero)(_ merge _).left.map(failed)
    } yield Balance.from(balance)
  }
}
