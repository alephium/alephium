package org.alephium.flow.core.validation

import org.alephium.flow.core._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.{AVector, Forest, TimeStamp}

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

  private[validation] def validateHeaderUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow,
      isSyncing: Boolean): HeaderValidationResult[Unit] = {
    for {
      _ <- checkTimeStamp(header, isSyncing)
      _ <- checkWorkAmount(header)
      _ <- checkDependencies(header, flow)
    } yield ()
  }

  private[validation] def validateHeaderAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformConfig): HeaderValidationResult[Unit] = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
    } yield ()
  }

  private[validation] def validateHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): HeaderValidationResult[Unit] = {
    for {
      _ <- validateHeaderUntilDependencies(header, flow, isSyncing)
      _ <- validateHeaderAfterDependencies(header, flow)
    } yield ()
  }

  private[validation] def validateBlockUntilDependencies(
      block: Block,
      flow: BlockFlow,
      isSyncing: Boolean): BlockValidationResult[Unit] = {
    validateHeaderUntilDependencies(block.header, flow, isSyncing)
  }

  private[validation] def validateBlockAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- validateHeaderAfterDependencies(block.header, flow)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): BlockValidationResult[Unit] = {
    for {
      _ <- validateHeader(block.header, flow, isSyncing)
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

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private[validation] def checkTimeStamp(header: BlockHeader,
                                         isSyncing: Boolean): HeaderValidationResult[Unit] = {
    val now      = TimeStamp.now()
    val headerTs = header.timestamp

    val ok1 = headerTs < now.plusHoursUnsafe(1)
    val ok2 = isSyncing || (headerTs > now.plusHoursUnsafe(-1)) // Note: now -1hour is always positive
    if (ok1 && ok2) validHeader(()) else invalidHeader(InvalidTimeStamp)
  }

  private[validation] def checkWorkAmount[T <: FlowData](data: T): HeaderValidationResult[Unit] = {
    val current = BigInt(1, data.hash.bytes.toArray)
    assert(current >= 0)
    if (current <= data.target) validHeader(()) else invalidHeader(InvalidWorkAmount)
  }

  private[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult[Unit] = {
    headerChain.getHashTarget(header.parentHash) match {
      case Left(error) => Left(Left(error))
      case Right(target) =>
        if (target == header.target) validHeader(()) else invalidHeader(InvalidWorkTarget)
    }
  }

  private[validation] def checkDependencies(header: BlockHeader,
                                            flow: BlockFlow): HeaderValidationResult[Unit] = {
    header.blockDeps.filterNotE(flow.contains) match {
      case Left(error) => Left(Left(error))
      case Right(missings) =>
        if (missings.isEmpty) validHeader(()) else invalidHeader(MissingDeps(missings))
    }
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

  private[validation] def checkNonEmpty(tx: Transaction): TxValidationResult[Unit] = {
    if (tx.unsigned.inputs.isEmpty) {
      invalidTx(EmptyInputs)
    } else if (tx.outputsLength == 0) {
      invalidTx(EmptyOutputs)
    } else {
      validTx(())
    }
  }

  private[validation] def checkOutputValue(tx: Transaction): TxValidationResult[Unit] = {
    tx.alfAmountInOutputs match {
      case Some(sum) if sum < ALF.MaxALFValue => validTx(())
      case _                                  => invalidTx(BalanceOverFlow)
    }
  }

  private def getToGroup(output: TxOutput, default: GroupIndex)(
      implicit config: GroupConfig): GroupIndex = output match {
    case assetOutput: AssetOutput => assetOutput.toGroup
    case _: ContractOutput        => default
  }

  private[validation] def checkChainIndex(index: ChainIndex, tx: Transaction)(
      implicit config: GroupConfig): TxValidationResult[Unit] = {
    val fromOk    = tx.unsigned.inputs.forall(_.fromGroup == index.from)
    val fromGroup = tx.fromGroup
    val toGroups  = (0 until tx.outputsLength).map(i => getToGroup(tx.getOutput(i), fromGroup))
    val toOk      = toGroups.forall(groupIndex => groupIndex == index.from || groupIndex == index.to)
    val existed   = toGroups.view.take(tx.unsigned.fixedOutputs.length).contains(index.to)
    if (fromOk && toOk && existed) validTx(()) else invalidTx(InvalidChainIndex)
  }

  private[validation] def checkBlockDoubleSpending(block: Block): TxValidationResult[Unit] = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    block.nonCoinbase.foreachE { tx =>
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) invalidTx(DoubleSpent)
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
    data.chainIndex == index && checkWorkAmount(data).isRight
  }
}
// scalastyle:on number.of.methods
