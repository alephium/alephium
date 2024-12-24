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

import org.alephium.api.{badRequest, Try}
import org.alephium.api.model.{BuildGrouplessTransferTx, BuildTransferTxResult}
import org.alephium.flow.core.{BlockFlow, FlowUtils, TxUtils}
import org.alephium.protocol.ALPH
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasPrice, LockupScript, UnlockScript}
import org.alephium.util.{AVector, Math, U256}

trait GrouplessUtils { self: ServerUtils =>
  def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessTransferTx
  ): Try[AVector[BuildTransferTxResult]] = {
    val outputInfos = prepareOutputInfos(query.destinations)
    val gasPrice    = query.gasPrice.getOrElse(nonCoinbaseMinGasPrice)
    val result = for {
      lockPair <- query.lockPair
      totalAmount <- blockFlow.checkAndCalcTotalAmountNeeded(
        lockPair._1,
        outputInfos,
        None,
        gasPrice
      )
      utxos <- blockFlow
        .getUsableUtxos(query.targetBlockHash, lockPair._1, self.apiConfig.defaultUtxosLimit)
        .left
        .map(_.getMessage)
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
    result.left.map(badRequest)
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
  ): Either[String, AVector[UnsignedTransaction]] = {
    blockFlow.transfer(lockup, unlock, outputInfos, totalAmount, utxos, None, gasPrice) match {
      case Right(unsignedTx) => Right(AVector(unsignedTx))
      case Left(_) =>
        val (alphNeeded, tokenNeeded, _)       = totalAmount
        val (alphBalance, _, tokenBalances, _) = TxUtils.getBalance(utxos.map(_.output))
        val maxGasFee                          = gasPrice * getMaximalGasPerTx()
        val remainAlph = maxGasFee.addUnsafe(alphNeeded).sub(alphBalance).getOrElse(U256.Zero)
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
          Left(s"Not enough token balances, requires additional $message")
        } else {
          buildGrouplessTransferTx(
            blockFlow,
            lockup,
            unlock,
            result._1,
            utxos,
            outputInfos,
            totalAmount,
            gasPrice
          ).left.map { error =>
            if (result._2.nonZero) {
              s"Not enough ALPH balance, requires an additional ${ALPH.prettifyAmount(result._2)}"
            } else {
              error
            }
          }
        }
    }
  }

  private def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      lockup: LockupScript.P2PK,
      unlock: UnlockScript,
      txs: AVector[UnsignedTransaction],
      utxos: AVector[FlowUtils.AssetOutputInfo],
      outputInfos: AVector[UnsignedTransaction.TxOutputInfo],
      totalAmount: UnsignedTransaction.TotalAmountNeeded,
      gasPrice: GasPrice
  ): Either[String, AVector[UnsignedTransaction]] = {
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
    val allUtxos = utxos ++ AVector.from(extraUtxos)
    blockFlow
      .transfer(lockup, unlock, outputInfos, totalAmount, allUtxos, None, gasPrice)
      .map(txs :+ _)
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
        val (alphBalance, _, tokenBalances, _) = TxUtils.getBalance(utxos.map(_.output))
        val (remainTokens, transferTokens)     = calcTokenAmount(tokenBalances, tokenAmounts)
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
}
