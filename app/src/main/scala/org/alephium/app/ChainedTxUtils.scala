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
import org.alephium.flow.gasestimation.{
  AssetScriptGasEstimator,
  GasEstimation,
  GasEstimationMultiplier
}
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.model.UnsignedTransaction.TotalAmountNeeded
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript, UnlockScript}
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util.{AVector, Math, TimeStamp, U256}

trait ChainedTxUtils { self: ServerUtils =>
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
      targetBlockHash: Option[BlockHash],
      extraDustAmount: U256
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
        targetBlockHash,
        extraDustAmount
      )
    } yield {
      val (transferTxs, executeScriptTx, txScriptExecution) = result
      val executeScriptTxResult = BuildSimpleExecuteScriptTxResult.from(
        executeScriptTx,
        SimulationResult.from(txScriptExecution)
      )
      BuildGrouplessExecuteScriptTxResult.from(
        executeScriptTxResult,
        transferTxs.map(BuildSimpleTransferTxResult.from)
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
        targetBlockHash,
        U256.Zero
      )
    } yield {
      val (transferTxs, deployContractTx, _) = result
      BuildGrouplessDeployContractTxResult.from(
        BuildSimpleDeployContractTxResult.from(deployContractTx),
        transferTxs.map(BuildSimpleTransferTxResult.from)
      )
    }
  }
  // scalastyle:off parameter.number method.length
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
      targetBlockHash: Option[BlockHash],
      extraDustAmount: U256
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
              targetBlockHash,
              extraDustAmount
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
  // scalastyle:on parameter.number method.length

  private def buildTransferTxsFromFallbackAddresses(
      blockFlow: BlockFlow,
      lockup: LockupScript.Asset,
      otherLockupPairs: AVector[(LockupScript.Asset, UnlockScript)],
      totalAmountNeeded: TotalAmountNeeded,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      gasPrice: GasPrice,
      targetBlockHash: Option[BlockHash],
      extraDustAmount: U256
  ): Try[(AVector[UnsignedTransaction], U256, AVector[(TokenId, U256)])] = {
    val (alphBalance, tokenBalances) = getAvailableBalances(utxos)
    val maxGasFee                    = gasPrice * getMaximalGasPerTx()
    val remainAlph =
      maxGasFee
        .addUnsafe(totalAmountNeeded.alphAmount)
        .addUnsafe(extraDustAmount)
        .sub(alphBalance)
        .getOrElse(U256.Zero)
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
        val transferAlph =
          calcAlphAmount(
            fromLockupPair,
            alphBalance,
            alphAmount,
            transferTokens.length,
            utxos.length,
            gasPrice
          )
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
      lockPair: (LockupScript.Asset, UnlockScript),
      alphBalance: U256,
      alphNeeded: U256,
      tokenSize: Int,
      numOfInputs: Int,
      gasPrice: GasPrice
  ): U256 = {
    val tokenDustAmount  = U256.unsafe(tokenSize).mulUnsafe(dustUtxoAmount)
    val changeDustAmount = U256.unsafe(tokenSize + 1).mulUnsafe(dustUtxoAmount)
    if (alphNeeded <= tokenDustAmount) {
      tokenDustAmount
    } else {
      // The funding tx outputs: destination (tokenSize + 1) + sender change (tokenSize + 1)
      val numOutputs = 2 * (tokenSize + 1)
      val maxGasFee =
        estimateMaxTransferGasFee(lockPair._1, lockPair._2, numOfInputs, numOutputs, gasPrice)
      val extraAmount = tokenDustAmount.addUnsafe(changeDustAmount).addUnsafe(maxGasFee)
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

  protected def estimateMaxTransferGasFee(
      lockupScript: LockupScript,
      unlockScript: UnlockScript,
      numOfInputs: Int,
      numOutputs: Int,
      gasPrice: GasPrice
  ): U256 = {
    GasEstimation
      .estimateWithInputScript(
        (lockupScript, unlockScript),
        numOfInputs,
        numOutputs,
        AssetScriptGasEstimator.NotImplemented
      ) match {
      case Right(gas) => gasPrice * gas
      case Left(_)    => gasPrice * getMaximalGasPerTx()
    }
  }
}
