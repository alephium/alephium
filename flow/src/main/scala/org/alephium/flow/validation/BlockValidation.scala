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
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.{Block, TxOutputRef}
import org.alephium.util.U256

trait BlockValidation extends Validation[Block, InvalidBlockStatus] {
  import ValidationStatus._

  def headerValidation: HeaderValidation
  def nonCoinbaseValidation: NonCoinbaseValidation

  override def validate(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    checkBlock(block, flow)
  }

  override def validateUntilDependencies(block: Block,
                                         flow: BlockFlow): BlockValidationResult[Unit] = {
    checkBlockUntilDependencies(block, flow)
  }

  def validateAfterDependencies(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    checkBlockAfterDependencies(block, flow)
  }

  def validateAfterHeader(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    checkBlockAfterHeader(block, flow)
  }

  private[validation] def checkBlockUntilDependencies(
      block: Block,
      flow: BlockFlow): BlockValidationResult[Unit] = {
    headerValidation.checkHeaderUntilDependencies(block.header, flow)
  }

  private[validation] def checkBlockAfterDependencies(
      block: Block,
      flow: BlockFlow): BlockValidationResult[Unit] = {
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

  private[validation] def checkBlockAfterHeader(block: Block,
                                                flow: BlockFlow): BlockValidationResult[Unit] = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbaseEasy(block)
      _ <- checkMerkleRoot(block)
      _ <- checkNonCoinbases(block, flow)
      _ <- checkCoinbaseReward(block)
      _ <- checkFlow(block, flow)
    } yield ()
  }

  private[validation] def checkGroup(block: Block): BlockValidationResult[Unit] = {
    if (block.chainIndex.relateTo(brokerConfig)) validBlock(())
    else invalidBlock(InvalidGroup)
  }

  private[validation] def checkNonEmptyTransactions(block: Block): BlockValidationResult[Unit] = {
    if (block.transactions.nonEmpty) validBlock(()) else invalidBlock(EmptyTransactionList)
  }

  private[validation] def checkCoinbaseEasy(block: Block): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (unsigned.inputs.isEmpty &&
        unsigned.fixedOutputs.length == 1 &&
        coinbase.generatedOutputs.isEmpty &&
        coinbase.inputSignatures.isEmpty &&
        coinbase.contractSignatures.isEmpty) {
      validBlock(())
    } else {
      invalidBlock(InvalidCoinbaseFormat)
    }
  }

  // TODO: use Merkle hash for transactions
  private[validation] def checkMerkleRoot(block: Block): BlockValidationResult[Unit] = {
    if (block.header.txsHash == Hash.hash(block.transactions)) validBlock(())
    else invalidBlock(InvalidMerkleRoot)
  }

  private[validation] def checkNonCoinbases(block: Block,
                                            flow: BlockFlow): BlockValidationResult[Unit] = {
    val index = block.chainIndex
    assume(index.relateTo(brokerConfig))

    if (brokerConfig.contains(index.from)) {
      val result = for {
        _    <- checkBlockDoubleSpending(block)
        trie <- ValidationStatus.from(flow.getCachedTrie(block))
        _ <- block.getNonCoinbaseExecutionOrder.foldE(trie) {
          case (currentTrie, index) =>
            nonCoinbaseValidation.checkBlockTx(block.transactions(index), currentTrie)
        }
      } yield ()
      convert(result)
    } else {
      validBlock(())
    }
  }

  private[validation] def checkBlockDoubleSpending(block: Block): TxValidationResult[Unit] = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    block.nonCoinbase.foreachE { tx =>
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) invalidTx(DoubleSpending)
        else {
          utxoUsed += input.outputRef
          validTx(())
        }
      }
    }
  }

  private[validation] def checkCoinbaseReward(block: Block): BlockValidationResult[Unit] = {
    val gasFee = block.nonCoinbase.fold(U256.Zero)(_ addUnsafe _.gasFeeUnsafe)
    if (block.coinbaseReward == ALF.MinerReward.addUnsafe(gasFee)) validBlock(())
    else invalidBlock(InvalidCoinbaseReward)
  }

  private[validation] def checkFlow(block: Block, blockFlow: BlockFlow)(
      implicit brokerConfig: BrokerConfig): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      for {
        _ <- ValidationStatus.from(blockFlow.checkFlowDeps(block)).flatMap { ok =>
          if (ok) validBlock(()) else invalidBlock(InvalidFlowDeps)
        }
        _ <- ValidationStatus.from(blockFlow.checkFlowTxs(block)).flatMap { ok =>
          if (ok) validBlock(()) else invalidBlock(InvalidFlowTxs)
        }
      } yield ()
    } else {
      ValidationStatus.from(blockFlow.checkFlowDeps(block.header)).flatMap { ok =>
        if (ok) validBlock(()) else invalidBlock(InvalidHeaderFlow)
      }
    }
  }
}

object BlockValidation {
  def build(implicit brokerConfig: BrokerConfig,
            consensusConfig: ConsensusConfig): BlockValidation = new Impl()

  class Impl(implicit val brokerConfig: BrokerConfig, val consensusConfig: ConsensusConfig)
      extends BlockValidation {
    override def headerValidation: HeaderValidation           = HeaderValidation.build
    override def nonCoinbaseValidation: NonCoinbaseValidation = NonCoinbaseValidation.build
  }
}
