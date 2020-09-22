package org.alephium.flow.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.{Block, TxOutputRef}

trait BlockValidation extends Validation[Block, BlockStatus] {
  import ValidationStatus._

  def headerValidation: HeaderValidation
  def nonCoinbaseValidation: NonCoinbaseValidation

  override def validate(block: Block, flow: BlockFlow): IOResult[BlockStatus] = {
    convert(checkBlock(block, flow), ValidBlock)
  }

  override def validateUntilDependencies(block: Block, flow: BlockFlow): IOResult[BlockStatus] = {
    convert(checkBlockUntilDependencies(block, flow), ValidBlock)
  }

  def validateAfterDependencies(block: Block, flow: BlockFlow): IOResult[BlockStatus] = {
    convert(checkBlockAfterDependencies(block, flow), ValidBlock)
  }

  def validateAfterHeader(block: Block, flow: BlockFlow): IOResult[BlockStatus] = {
    convert(checkBlockAfterHeader(block, flow), ValidBlock)
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
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      _ <- checkNonCoinbases(block, flow)
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

  private[validation] def checkCoinbase(block: Block): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (unsigned.inputs.isEmpty && unsigned.fixedOutputs.length == 1 && coinbase.generatedOutputs.isEmpty && coinbase.signatures.isEmpty)
      validBlock(())
    else invalidBlock(InvalidCoinbase)
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
        trie <- ValidationStatus.from(flow.getPersistedTrie(block))
        _    <- block.nonCoinbase.foreachE(nonCoinbaseValidation.checkBlockTx(_, trie))
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

  private[validation] def checkFlow(block: Block, blockFlow: BlockFlow)(
      implicit brokerConfig: BrokerConfig): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      ValidationStatus.from(blockFlow.checkFlowBlock(block)).flatMap { ok =>
        if (ok) validBlock(()) else invalidBlock(InvalidBlockFlow)
      }
    } else {
      ValidationStatus.from(blockFlow.checkFlowHeader(block.header)).flatMap { ok =>
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
