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

import org.alephium.flow.core.{BlockFlow, BlockFlowGroupView}
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig, NetworkConfig}
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockEnv, GasPrice, WorldState}
import org.alephium.serde._
import org.alephium.util.U256

trait BlockValidation extends Validation[Block, InvalidBlockStatus, Option[WorldState.Cached]] {
  import ValidationStatus._

  implicit def networkConfig: NetworkConfig

  def headerValidation: HeaderValidation
  def nonCoinbaseValidation: TxValidation

  override def validate(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    checkBlock(block, flow)
  }

  def validateTemplate(
      chainIndex: ChainIndex,
      template: BlockFlowTemplate,
      blockFlow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    val dummyHeader = BlockHeader.unsafe(
      BlockDeps.unsafe(template.deps),
      template.depStateHash,
      Hash.zero,
      template.templateTs,
      template.target,
      Nonce.zero
    )
    val dummyBlock = Block(dummyHeader, template.transactions)
    checkTemplate(chainIndex, dummyBlock, blockFlow)
  }

  // keep the commented lines so we could compare it easily with `checkBlockAfterHeader`
  def checkTemplate(
      chainIndex: ChainIndex,
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
//      _ <- checkGroup(block)
//      _ <- checkNonEmptyTransactions(block)
      _ <- checkTxNumber(block)
      _ <- checkGasPriceDecreasing(block)
      _ <- checkTotalGas(block)
//      _ <- checkMerkleRoot(block)
//      _ <- checkFlow(block, flow)
      sideResult <- checkTxs(chainIndex, block, flow)
    } yield sideResult
  }

  override def validateUntilDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    checkBlockUntilDependencies(block, flow)
  }

  def validateAfterDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
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
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
      _          <- headerValidation.checkHeaderAfterDependencies(block.header, flow)
      sideResult <- checkBlockAfterHeader(block, flow)
    } yield sideResult
  }

  private[validation] def checkBlock(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
      _          <- headerValidation.checkHeader(block.header, flow)
      sideResult <- checkBlockAfterHeader(block, flow)
    } yield sideResult
  }

  private[flow] def checkBlockAfterHeader(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
      _          <- checkGroup(block)
      _          <- checkNonEmptyTransactions(block)
      _          <- checkTxNumber(block)
      _          <- checkGasPriceDecreasing(block)
      _          <- checkTotalGas(block)
      _          <- checkMerkleRoot(block)
      _          <- checkFlow(block, flow)
      sideResult <- checkTxs(block.chainIndex, block, flow)
    } yield sideResult
  }

  private def checkTxs(
      chainIndex: ChainIndex,
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    if (brokerConfig.contains(chainIndex.from)) {
      for {
        groupView <- from(flow.getMutableGroupView(chainIndex.from, block.blockDeps))
        _         <- checkNonCoinbases(chainIndex, block, groupView)
        _         <- checkCoinbase(chainIndex, block, groupView) // validate non-coinbase first for gas fee
      } yield {
        if (chainIndex.isIntraGroup) Some(groupView.worldState) else None
      }
    } else {
      validBlock(None)
    }
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

  private[validation] def checkGasPriceDecreasing(block: Block): BlockValidationResult[Unit] = {
    val result = block.transactions.foldE[Unit, GasPrice](GasPrice(ALPH.MaxALPHValue)) {
      case (lastGasPrice, tx) =>
        val txGasPrice = tx.unsigned.gasPrice
        if (txGasPrice > lastGasPrice) Left(()) else Right(txGasPrice)
    }
    if (result.isRight) validBlock(()) else invalidBlock(TxGasPriceNonDecreasing)
  }

  // Let's check the gas is decreasing as well
  private[validation] def checkTotalGas(block: Block): BlockValidationResult[Unit] = {
    val totalGas = block.transactions.fold(0)(_ + _.unsigned.gasAmount.value)
    if (totalGas <= maximalGasPerBlock.value) validBlock(()) else invalidBlock(TooMuchGasUsed)
  }

  private[validation] def checkCoinbase(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): BlockValidationResult[Unit] = {
    val result = consensusConfig.emission.reward(block.header) match {
      case Emission.PoW(miningReward) =>
        val netReward = Transaction.totalReward(block.gasFee, miningReward)
        checkCoinbase(chainIndex, block, groupView, 1, netReward, netReward)
      case Emission.PoLW(miningReward, burntAmount) =>
        val lockedReward = Transaction.totalReward(block.gasFee, miningReward)
        val netReward    = lockedReward.subUnsafe(burntAmount)
        checkCoinbase(chainIndex, block, groupView, 2, netReward, lockedReward)
    }

    result match {
      case Left(Right(ExistInvalidTx(InvalidAlphBalance))) => Left(Right(InvalidCoinbaseReward))
      case result                                          => result
    }
  }

  private[validation] def checkCoinbase(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      outputNum: Int,
      netReward: U256,
      lockedReward: U256
  ): BlockValidationResult[Unit] = {
    for {
      _ <- checkCoinbaseEasy(block, outputNum)
      _ <- checkCoinbaseData(chainIndex, block)
      _ <- checkCoinbaseAsTx(chainIndex, block, groupView, netReward.addUnsafe(minimalGasFee))
      _ <- checkLockedReward(block, lockedReward)
    } yield ()
  }

  private[validation] def checkCoinbaseAsTx(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      netReward: U256
  ): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val blockEnv = BlockEnv.from(block.header)
      convert(
        nonCoinbaseValidation.checkBlockTx(
          chainIndex,
          block.coinbase,
          groupView,
          blockEnv,
          Some(netReward)
        )
      )
    } else {
      validBlock(())
    }
  }

  private[validation] def checkCoinbaseEasy(
      block: Block,
      outputsNum: Int
  ): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (
      unsigned.scriptOpt.isEmpty &&
      unsigned.gasAmount == minimalGas &&
      unsigned.gasPrice == minimalGasPrice &&
      unsigned.fixedOutputs.length == outputsNum &&
      unsigned.fixedOutputs(0).tokens.isEmpty &&
      coinbase.contractInputs.isEmpty &&
      coinbase.generatedOutputs.isEmpty &&
      coinbase.inputSignatures.isEmpty &&
      coinbase.scriptSignatures.isEmpty
    ) {
      validBlock(())
    } else {
      invalidBlock(InvalidCoinbaseFormat)
    }
  }

  private[validation] def checkCoinbaseData(
      chainIndex: ChainIndex,
      block: Block
  ): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase
    val data     = coinbase.unsigned.fixedOutputs.head.additionalData
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

  private[validation] def checkLockedReward(
      block: Block,
      lockedAmount: U256
  ): BlockValidationResult[Unit] = {
    val output = block.coinbase.unsigned.fixedOutputs.head
    if (output.amount != lockedAmount) {
      invalidBlock(InvalidCoinbaseLockedAmount)
    } else if (output.lockTime != block.timestamp.plusUnsafe(networkConfig.coinbaseLockupPeriod)) {
      invalidBlock(InvalidCoinbaseLockupPeriod)
    } else {
      validBlock(())
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
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): BlockValidationResult[Unit] = {
    assume(chainIndex.relateTo(brokerConfig))

    if (brokerConfig.contains(chainIndex.from)) {
      for {
        _ <- checkBlockDoubleSpending(block)
        _ <- convert(checkNonCoinbasesEach(chainIndex, block, groupView))
      } yield ()
    } else {
      validBlock(())
    }
  }

  private[validation] def checkNonCoinbasesEach(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): TxValidationResult[Unit] = {
    val blockEnv       = BlockEnv.from(block.header)
    val parentHash     = block.blockDeps.uncleHash(chainIndex.to)
    val executionOrder = Block.getNonCoinbaseExecutionOrder(parentHash, block.nonCoinbase)
    executionOrder.foreachE { index =>
      val tx = block.transactions(index)
      val txValidationResult = nonCoinbaseValidation.checkBlockTx(
        chainIndex,
        block.transactions(index),
        groupView,
        blockEnv,
        None
      )
      txValidationResult match {
        case Right(_) => Right(())
        case Left(Right(TxScriptExeFailed(_))) =>
          if (tx.contractInputs.isEmpty) {
            Right(())
          } else {
            invalidTx(ContractInputsShouldBeEmptyForFailedTxScripts)
          }
        case result => result
      }
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
  def build(blockFlow: BlockFlow): BlockValidation =
    build(blockFlow.brokerConfig, blockFlow.networkConfig, blockFlow.consensusConfig)

  def build(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusConfig: ConsensusConfig
  ): BlockValidation = new Impl()

  class Impl(implicit
      val brokerConfig: BrokerConfig,
      val networkConfig: NetworkConfig,
      val consensusConfig: ConsensusConfig
  ) extends BlockValidation {
    override def headerValidation: HeaderValidation  = HeaderValidation.build
    override def nonCoinbaseValidation: TxValidation = TxValidation.build
  }
}
