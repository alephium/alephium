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

package org.alephium.flow.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.U256

trait BlockValidation extends Validation[Block, InvalidBlockStatus] {
  import ValidationStatus._

  def headerValidation: HeaderValidation
  def nonCoinbaseValidation: TxValidation

  override def validate(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    checkBlock(block, flow)
  }

  override def validateUntilDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    checkBlockUntilDependencies(block, flow)
  }

  def validateAfterDependencies(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    checkBlockAfterDependencies(block, flow)
  }

  private[validation] def checkBlockUntilDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    headerValidation.checkHeaderUntilDependencies(block.header, flow)
  }

  private[validation] def checkBlockAfterDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    for {
      _ <- headerValidation.checkHeaderAfterDependencies(block.header, flow)
      _ <- checkBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def checkBlock(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    for {
      _ <- headerValidation.checkHeader(block.header, flow)
      _ <- checkBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def checkBlockAfterHeader(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkTxNumber(block)
      _ <- checkTotalGas(block)
      _ <- checkMerkleRoot(block)
      _ <- checkFlow(block, flow)
      _ <- checkNonCoinbases(block, flow)
      _ <- checkCoinbase(block, flow) // validate non-coinbase first for gas fee
    } yield ()
  }

  private[validation] def checkGroup(block: Block): BlockValidationResult[Unit] = {
    if (block.chainIndex.relateTo(brokerConfig)) {
      validBlock(())
    } else {
      invalidBlock(InvalidGroup)
    }
  }

  private[validation] def checkNonEmptyTransactions(block: Block): BlockValidationResult[Unit] = {
    if (block.transactions.nonEmpty) validBlock(()) else invalidBlock(EmptyTransactionList)
  }

  private[validation] def checkTxNumber(block: Block): BlockValidationResult[Unit] = {
    if (block.transactions.length <= maximalTxsInOneBlock) {
      validBlock(())
    } else {
      invalidBlock(TooManyTransactions)
    }
  }

  private[validation] def checkTotalGas(block: Block): BlockValidationResult[Unit] = {
    val totalGas = block.transactions.fold(0)(_ + _.unsigned.startGas.value)
    if (totalGas <= maximalGas.value) validBlock(()) else invalidBlock(TooManyGasUsed)
  }

  private[validation] def checkCoinbase(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    val result = if (block.header.isPoLWEnabled) {
      checkCoinbaseWithPoLW(block, flow)
    } else {
      checkCoinbaseWithoutPoLW(block, flow)
    }

    result match {
      case Left(Right(ExistInvalidTx(InvalidAlfBalance))) => Left(Right(InvalidCoinbaseReward))
      case result                                         => result
    }
  }

  private[validation] def checkCoinbaseWithoutPoLW(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    val reward    = consensusConfig.emission.miningReward(block.header)
    val netReward = reward.addUnsafe(block.gasFee)
    checkCoinbase(block, flow, 0, 1, netReward)
  }

  private[validation] def checkCoinbaseWithPoLW(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    val reward      = consensusConfig.emission.miningReward(block.header)
    val burntAmount = consensusConfig.emission.burntAmountUnsafe(block.target, reward)
    val netReward   = reward.addUnsafe(block.gasFee).subUnsafe(burntAmount)
    checkCoinbase(block, flow, 1, 2, netReward)
  }

  private[validation] def checkCoinbase(
      block: Block,
      flow: BlockFlow,
      inputNum: Int,
      outputNum: Int,
      netReward: U256
  ): BlockValidationResult[Unit] = {
    for {
      _ <- checkCoinbaseEasy(block, inputNum, outputNum)
      _ <- checkCoinbaseData(block)
      _ <- checkCoinbaseAsTx(block, flow, netReward)
    } yield ()
  }

  private[validation] def checkCoinbaseAsTx(
      block: Block,
      flow: BlockFlow,
      netReward: U256
  ): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      for {
        worldState <- ValidationStatus.from(flow.getCachedWorldState(block))
        _ <- convert(
          nonCoinbaseValidation.checkCoinbase(block.coinbase, block.header, worldState, netReward)
        )
      } yield ()
    } else {
      validBlock(())
    }
  }

  private[validation] def checkCoinbaseEasy(
      block: Block,
      inputsNum: Int,
      outputsNum: Int
  ): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (
      unsigned.scriptOpt.isEmpty &&
      unsigned.startGas == minimalGas &&
      unsigned.gasPrice == defaultGasPrice &&
      unsigned.inputs.length == inputsNum &&
      unsigned.fixedOutputs.length == outputsNum &&
      unsigned.fixedOutputs(0).tokens.isEmpty &&
      coinbase.contractInputs.isEmpty &&
      coinbase.generatedOutputs.isEmpty &&
      coinbase.inputSignatures.isEmpty &&
      coinbase.contractSignatures.isEmpty
    ) {
      validBlock(())
    } else {
      invalidBlock(InvalidCoinbaseFormat)
    }
  }

  private[validation] def checkCoinbaseData(block: Block): BlockValidationResult[Unit] = {
    val coinbase   = block.coinbase
    val chainIndex = block.chainIndex
    val data       = coinbase.unsigned.fixedOutputs.head.additionalData
    _deserialize[CoinbaseFixedData](data) match {
      case Right(Staging(coinbaseFixedData, _)) =>
        if (
          coinbaseFixedData.fromGroup == chainIndex.from.value.toByte &&
          coinbaseFixedData.toGroup == chainIndex.to.value.toByte &&
          coinbaseFixedData.blockTs == block.header.timestamp
        ) {
          validBlock(())
        } else {
          invalidBlock(InvalidCoinbaseData)
        }
      case Left(_) => invalidBlock(InvalidCoinbaseData)
    }
  }

  private[validation] def checkMerkleRoot(block: Block): BlockValidationResult[Unit] = {
    if (block.header.txsHash == Block.calTxsHash(block.transactions)) {
      validBlock(())
    } else {
      invalidBlock(InvalidTxsMerkleRoot)
    }
  }

  private[validation] def checkNonCoinbases(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.relateTo(brokerConfig))

    if (brokerConfig.contains(chainIndex.from)) {
      for {
        _          <- checkBlockDoubleSpending(block)
        worldState <- ValidationStatus.from(flow.getCachedWorldState(block))
        _ <-
          convert(block.getNonCoinbaseExecutionOrder.foreachE { index =>
            nonCoinbaseValidation.checkBlockTx(
              chainIndex,
              block.transactions(index),
              block.header,
              worldState
            )
          })
      } yield ()
    } else {
      validBlock(())
    }
  }

  private[validation] def checkBlockDoubleSpending(block: Block): BlockValidationResult[Unit] = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    block.nonCoinbase.foreachE { tx =>
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) {
          invalidBlock(BlockDoubleSpending)
        } else {
          utxoUsed += input.outputRef
          validBlock(())
        }
      }
    }
  }

  private[validation] def checkFlow(block: Block, blockFlow: BlockFlow)(implicit
      brokerConfig: BrokerConfig
  ): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      ValidationStatus.from(blockFlow.checkFlowTxs(block)).flatMap { ok =>
        if (ok) validBlock(()) else invalidBlock(InvalidFlowTxs)
      }
    } else {
      validBlock(())
    }
  }
}

object BlockValidation {
  def build(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig
  ): BlockValidation = new Impl()

  class Impl(implicit val brokerConfig: BrokerConfig, val consensusConfig: ConsensusConfig)
      extends BlockValidation {
    override def headerValidation: HeaderValidation  = HeaderValidation.build
    override def nonCoinbaseValidation: TxValidation = TxValidation.build
  }
}
