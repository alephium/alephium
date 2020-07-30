package org.alephium.flow.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{Block, TxOutputRef}

object BlockValidation extends Validation[Block, BlockStatus] {
  import ValidationStatus._

  override def validate(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[BlockStatus] = {
    convert(checkBlock(block, flow), ValidBlock)
  }

  override def validateUntilDependencies(block: Block, flow: BlockFlow)(
      implicit config: ConsensusConfig): IOResult[BlockStatus] = {
    convert(checkBlockUntilDependencies(block, flow), ValidBlock)
  }

  def validateAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[BlockStatus] = {
    convert(checkBlockAfterDependencies(block, flow), ValidBlock)
  }

  def validateAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[BlockStatus] = {
    convert(checkBlockAfterHeader(block, flow), ValidBlock)
  }

  private[validation] def checkBlockUntilDependencies(block: Block, flow: BlockFlow)(
      implicit config: ConsensusConfig): BlockValidationResult[Unit] = {
    HeaderValidation.checkHeaderUntilDependencies(block.header, flow)
  }

  private[validation] def checkBlockAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- HeaderValidation.checkHeaderAfterDependencies(block.header, flow)
      _ <- checkBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def checkBlock(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- HeaderValidation.checkHeader(block.header, flow)
      _ <- checkBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def checkBlockAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      _ <- checkNonCoinbases(block, flow)
    } yield ()
  }

  private[validation] def checkGroup(block: Block)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    if (block.chainIndex.relateTo(config.brokerInfo)) validBlock(())
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

  private[validation] def checkNonCoinbases(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    val index      = block.chainIndex
    val brokerInfo = config.brokerInfo
    assert(index.relateTo(brokerInfo))

    if (brokerInfo.contains(index.from)) {
      val result = for {
        _    <- checkBlockDoubleSpending(block)
        trie <- ValidationStatus.from(flow.getPersistedTrie(block))
        _    <- block.nonCoinbase.foreachE(NonCoinbaseValidation.checkBlockTx(_, trie))
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
}
