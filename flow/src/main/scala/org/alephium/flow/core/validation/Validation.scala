package org.alephium.flow.core.validation

import org.alephium.flow.core._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.{AVector, Forest}

abstract class Validation[T <: FlowData, S <: ValidationStatus]() {
  def validate(data: T, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): IOResult[S]

  def validateUntilDependencies(data: T, flow: BlockFlow, isSyncing: Boolean): IOResult[S]

  def validateAfterDependencies(data: T, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[S]
}

// scalastyle:off number.of.methods
object Validation {
  import ValidationStatus._

  private[validation] def validateBlockUntilDependencies(
      block: Block,
      flow: BlockFlow,
      isSyncing: Boolean): BlockValidationResult[Unit] = {
    HeaderValidation.checkHeaderUntilDependencies(block.header, flow, isSyncing)
  }

  private[validation] def validateBlockAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- HeaderValidation.checkHeaderAfterDependencies(block.header, flow)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- HeaderValidation.checkHeader(block.header, flow, isSyncing)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlockAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      _ <- checkNonCoinbases(block, flow)
    } yield ()
  }

  /*
   * The following functions are all the check functions behind validations
   */

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

  /*
   * The following functions are for other type of validation
   */

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def validateFlowDAG[T <: FlowData](datas: AVector[T])(
      implicit config: GroupConfig): Option[AVector[Forest[Hash, T]]] = {
    val splits = datas.splitBy(_.chainIndex)
    val builds = splits.map(ds => Forest.tryBuild[Hash, T](ds, _.hash, _.parentHash))
    if (builds.forall(_.nonEmpty)) Some(builds.map(_.get)) else None
  }

  def validateMined[T <: FlowData](data: T, index: ChainIndex)(
      implicit config: GroupConfig): Boolean = {
    data.chainIndex == index && HeaderValidation.checkWorkAmount(data).isRight
  }
}
// scalastyle:on number.of.methods
