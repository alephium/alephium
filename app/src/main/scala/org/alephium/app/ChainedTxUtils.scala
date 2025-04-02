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

import org.alephium.api.{badRequest, failedInIO, ApiError, Try}
import org.alephium.api.model._
import org.alephium.api.model.BuildTxCommon.ScriptTxAmounts
import org.alephium.flow.core.{BlockFlow, FlowUtils, TxUtils}
import org.alephium.flow.gasestimation.GasEstimationMultiplier
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.TotalAmountNeeded
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript, UnlockScript}
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util.{AVector, Math, TimeStamp, U256}

trait ChainedTxUtils { self: ServerUtils =>

  def buildTransferTxWithFallbackAddresses(
      blockFlow: BlockFlow,
      lockPair: (LockupScript.Asset, UnlockScript),
      otherLockPairs: AVector[(LockupScript.Asset, UnlockScript)],
      destinations: AVector[Destination],
      gasPriceOpt: Option[GasPrice],
      targetBlockHashOpt: Option[BlockHash]
  ): Try[AVector[BuildSimpleTransferTxResult]] = {
    val outputInfos = prepareOutputInfos(destinations)
    val gasPrice    = gasPriceOpt.getOrElse(nonCoinbaseMinGasPrice)
    for {
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
        .getUsableUtxos(targetBlockHashOpt, lockPair._1, self.apiConfig.defaultUtxosLimit)
        .left
        .map(failedInIO)
      txs <- buildTransferTxWithFallbackAddresses(
        blockFlow,
        lockPair,
        otherLockPairs,
        totalAmount,
        utxos,
        outputInfos,
        gasPrice,
        targetBlockHashOpt
      )
    } yield txs.map(BuildSimpleTransferTxResult.from)
  }

  // scalastyle:off parameter.number
  def buildExecuteScriptTxWithFallbackAddresses(
      blockFlow: BlockFlow,
      lockPair: (LockupScript.Asset, UnlockScript),
      otherLockPairs: AVector[(LockupScript.Asset, UnlockScript)],
      script: StatefulScript,
      amounts: ScriptTxAmounts,
      gasEstimationMultiplier: Option[Double],
      gasAmount: Option[GasBox],
      gasPrice: Option[GasPrice],
      targetBlockHash: Option[BlockHash]
  ): Try[BuildGrouplessExecuteScriptTxResult] = {
    for {
      multiplier <- GasEstimationMultiplier.from(gasEstimationMultiplier).left.map(badRequest)
      utxos <- blockFlow
        .getUsableUtxos(lockPair._1, apiConfig.defaultUtxosLimit)
        .left
        .map(failedInIO)
      result <- buildExecuteScriptTxWithFallbackAddresses(
        blockFlow,
        amounts,
        lockPair,
        otherLockPairs,
        script,
        utxos,
        multiplier,
        gasAmount,
        gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        targetBlockHash
      )
    } yield {
      val (transferTxs, executeScriptTx, txScriptExecution) = result
      BuildGrouplessExecuteScriptTxResult(
        transferTxs.map(BuildSimpleTransferTxResult.from),
        BuildSimpleExecuteScriptTxResult.from(
          executeScriptTx,
          SimulationResult.from(txScriptExecution)
        )
      )
    }
  }
  // scalastyle:on parameter.number

  def buildDeployContractTxWithFallbackAddresses(
      blockFlow: BlockFlow,
      lockPair: (LockupScript.Asset, UnlockScript),
      otherLockPairs: AVector[(LockupScript.Asset, UnlockScript)],
      deployContractParams: BuildTxCommon.DeployContractTx,
      gasAmount: Option[GasBox],
      gasPrice: Option[GasPrice],
      targetBlockHash: Option[BlockHash]
  ): Try[BuildGrouplessDeployContractTxResult] = {
    for {
      amounts <- deployContractParams.getAmounts.left.map(badRequest)
      (contractDeposit, scriptTxAmounts) = amounts
      script <- buildDeployContractTxScript(
        deployContractParams,
        contractDeposit,
        scriptTxAmounts.tokens,
        lockPair._1
      )
      utxos <- blockFlow
        .getUsableUtxos(lockPair._1, apiConfig.defaultUtxosLimit)
        .left
        .map(failedInIO)
      result <- buildExecuteScriptTxWithFallbackAddresses(
        blockFlow,
        scriptTxAmounts,
        lockPair,
        otherLockPairs,
        script,
        utxos,
        None,
        gasAmount,
        gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        targetBlockHash
      )
    } yield {
      val (transferTxs, deployContractTx, _) = result
      BuildGrouplessDeployContractTxResult(
        transferTxs.map(BuildSimpleTransferTxResult.from),
        BuildSimpleDeployContractTxResult.from(deployContractTx)
      )
    }
  }

  private def buildTransferTxWithFallbackAddresses(
      blockFlow: BlockFlow,
      lockPair: (LockupScript.Asset, UnlockScript),
      otherLockupPairs: AVector[(LockupScript.Asset, UnlockScript)],
      totalAmount: UnsignedTransaction.TotalAmountNeeded,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      outputInfos: AVector[UnsignedTransaction.TxOutputInfo],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Try[AVector[UnsignedTransaction]] = {
    val (lockup, unlock) = lockPair
    blockFlow.transfer(lockup, unlock, outputInfos, totalAmount, utxos, None, gasPrice) match {
      case Right(unsignedTx) => Right(AVector(unsignedTx))
      case Left(_) =>
        for {
          crossGroupTxs <-
            buildTransferTxsFromFallbackAddresses(
              blockFlow,
              lockup,
              otherLockupPairs,
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

  // scalastyle:off parameter.number
  def buildExecuteScriptTxWithFallbackAddresses(
      blockFlow: BlockFlow,
      amounts: BuildTxCommon.ScriptTxAmounts,
      lockPair: (LockupScript.Asset, UnlockScript),
      otherLockupPairs: AVector[(LockupScript.Asset, UnlockScript)],
      script: StatefulScript,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      multiplier: Option[GasEstimationMultiplier],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Try[(AVector[UnsignedTransaction], UnsignedTransaction, TxScriptExecution)] = {
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
        val totalAmount =
          TotalAmountNeeded(amounts.estimatedAlph, amounts.tokens, amounts.tokens.length + 1)
        for {
          crossGroupTxs <-
            buildTransferTxsFromFallbackAddresses(
              blockFlow,
              lockPair._1,
              otherLockupPairs,
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

  private def buildTransferTxsFromFallbackAddresses(
      blockFlow: BlockFlow,
      lockup: LockupScript.Asset,
      otherLockupPairs: AVector[(LockupScript.Asset, UnlockScript)],
      totalAmountNeeded: TotalAmountNeeded,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Try[(AVector[UnsignedTransaction], U256, AVector[(TokenId, U256)])] = {
    val (alphBalance, tokenBalances) = getAvailableBalances(utxos)
    val maxGasFee                    = gasPrice * getMaximalGasPerTx()
    val remainAlph =
      maxGasFee.addUnsafe(totalAmountNeeded.alphAmount).sub(alphBalance).getOrElse(U256.Zero)
    val (remainTokens, _) = calcTokenAmount(tokenBalances, totalAmountNeeded.tokens)
    val result = transferFromFallbackAddresses(
      blockFlow,
      lockup,
      otherLockupPairs,
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

  private def transferFromFallbackAddresses(
      blockFlow: BlockFlow,
      toLockupScript: LockupScript.Asset,
      otherLockupPairs: AVector[(LockupScript.Asset, UnlockScript)],
      alphAmount: U256,
      tokenAmounts: AVector[(TokenId, U256)],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): (AVector[UnsignedTransaction], U256, AVector[(TokenId, U256)]) = {
    otherLockupPairs
      .fold((AVector.empty[UnsignedTransaction], alphAmount, tokenAmounts)) {
        case ((txs, remainAlph, remainTokens), (fromLockup, fromUnlock)) =>
          if (remainAlph.isZero && remainTokens.isEmpty) {
            (txs, remainAlph, remainTokens)
          } else {
            transferFromFallbackAddress(
              blockFlow,
              (fromLockup, fromUnlock),
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

  protected def transferFromFallbackAddress(
      blockFlow: BlockFlow,
      fromLockupPair: (LockupScript.Asset, UnlockScript),
      toLockup: LockupScript.Asset,
      alphAmount: U256,
      tokenAmounts: AVector[(TokenId, U256)],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash]
  ): Either[String, (UnsignedTransaction, U256, AVector[(TokenId, U256)])] = {
    val fromLockup = fromLockupPair._1
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
        val fromUnlock = fromLockupPair._2
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

  protected def calcAlphAmount(
      alphBalance: U256,
      alphNeeded: U256,
      tokenSize: Int,
      gasPrice: GasPrice
  ): U256 = {
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

  private def buildTransferTx(
      blockFlow: BlockFlow,
      lockup: LockupScript.Asset,
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

  protected def calcTokenAmount(
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

  protected def getAvailableBalances(
      utxos: AVector[FlowUtils.AssetOutputInfo]
  ): (U256, AVector[(TokenId, U256)]) = {
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

  protected def getExtraUtxos(
      lockup: LockupScript.Asset,
      txs: AVector[UnsignedTransaction]
  ): AVector[FlowUtils.AssetOutputInfo] = {
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
}
