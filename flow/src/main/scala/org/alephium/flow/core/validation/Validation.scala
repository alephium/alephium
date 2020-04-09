package org.alephium.flow.core.validation

import org.alephium.flow.core._
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{GroupConfig, ScriptConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.script.{PubScript, Script, Witness}
import org.alephium.util.{AVector, EitherF, Forest, TimeStamp}

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
      isSyncing: Boolean): HeaderValidationResult = {
    for {
      _ <- checkTimeStamp(header, isSyncing)
      _ <- checkWorkAmount(header)
      _ <- checkDependencies(header, flow)
    } yield ()
  }

  private[validation] def validateHeaderAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformConfig): HeaderValidationResult = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
    } yield ()
  }

  private[validation] def validateHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): HeaderValidationResult = {
    for {
      _ <- validateHeaderUntilDependencies(header, flow, isSyncing)
      _ <- validateHeaderAfterDependencies(header, flow)
    } yield ()
  }

  private[validation] def validateBlockUntilDependencies(
      block: Block,
      flow: BlockFlow,
      isSyncing: Boolean): BlockValidationResult = {
    validateHeaderUntilDependencies(block.header, flow, isSyncing)
  }

  private[validation] def validateBlockAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult = {
    for {
      _ <- validateHeaderAfterDependencies(block.header, flow)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): BlockValidationResult = {
    for {
      _ <- validateHeader(block.header, flow, isSyncing)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlockAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      _ <- checkNonCoinbases(block, flow)
    } yield ()
  }

  private[validation] def validateNonCoinbaseTx(tx: Transaction, flow: BlockFlow)(
      implicit config: PlatformConfig): TxValidationResult = {
    val index = ChainIndex(tx.fromGroup, tx.toGroup)
    val trie  = flow.getBestTrie(index)
    checkNonCoinbaseTx(index, tx, trie)
  }

  /*
   * The following functions are all the check functions behind validations
   */

  private[validation] def checkGroup(block: Block)(
      implicit config: PlatformConfig): BlockValidationResult = {
    if (block.chainIndex.relateTo(config.brokerInfo)) validBlock
    else invalidBlock(InvalidGroup)
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private[validation] def checkTimeStamp(header: BlockHeader,
                                         isSyncing: Boolean): HeaderValidationResult = {
    val now      = TimeStamp.now()
    val headerTs = header.timestamp

    val ok1 = headerTs < now.plusHoursUnsafe(1)
    val ok2 = isSyncing || (headerTs > now.plusHoursUnsafe(-1)) // Note: now -1hour is always positive
    if (ok1 && ok2) validHeader else invalidHeader(InvalidTimeStamp)
  }

  private[validation] def checkWorkAmount[T <: FlowData](data: T): HeaderValidationResult = {
    val current = BigInt(1, data.hash.bytes.toArray)
    assert(current >= 0)
    if (current <= data.target) validHeader else invalidHeader(InvalidWorkAmount)
  }

  private[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult = {
    headerChain.getHashTarget(header.parentHash) match {
      case Left(error) => Left(Left(error))
      case Right(target) =>
        if (target == header.target) validHeader else invalidHeader(InvalidWorkTarget)
    }
  }

  private[validation] def checkDependencies(header: BlockHeader,
                                            flow: BlockFlow): HeaderValidationResult = {
    val missings = header.blockDeps.filterNot(flow.contains)
    if (missings.isEmpty) validHeader else invalidHeader(MissingDeps(missings))
  }

  private[validation] def checkNonEmptyTransactions(block: Block): BlockValidationResult = {
    if (block.transactions.nonEmpty) validBlock else invalidBlock(EmptyTransactionList)
  }

  private[validation] def checkCoinbase(block: Block): BlockValidationResult = {
    val coinbase = block.transactions.last // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (unsigned.inputs.length == 0 && unsigned.outputs.length == 1 && coinbase.witnesses.isEmpty)
      validBlock
    else invalidBlock(InvalidCoinbase)
  }

  // TODO: use Merkle hash for transactions
  private[validation] def checkMerkleRoot(block: Block): BlockValidationResult = {
    if (block.header.txsHash == Hash.hash(block.transactions)) validBlock
    else invalidBlock(InvalidMerkleRoot)
  }

  private[validation] def checkNonCoinbases(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): TxsValidationResult = {
    val index      = block.chainIndex
    val brokerInfo = config.brokerInfo
    assert(index.relateTo(brokerInfo))

    if (brokerInfo.contains(index.from)) {
      val trie = flow.getTrie(block)
      val result = for {
        _ <- checkBlockDoubleSpending(block)
        _ <- block.transactions.init.foreachE(checkBlockNonCoinbase(index, _, trie))
      } yield ()
      convert(result)
    } else {
      validTxs
    }
  }

  private[validation] def checkBlockNonCoinbase(index: ChainIndex,
                                                tx: Transaction,
                                                trie: MerklePatriciaTrie)(
      implicit config: GroupConfig with ScriptConfig): TxValidationResult = {
    for {
      _ <- checkNonEmpty(tx)
      _ <- checkOutputValue(tx)
      _ <- checkChainIndex(index, tx)
      _ <- checkSpending(tx, trie)
    } yield ()
  }

  private[validation] def checkNonCoinbaseTx(index: ChainIndex,
                                             tx: Transaction,
                                             trie: MerklePatriciaTrie)(
      implicit config: GroupConfig with ScriptConfig): TxValidationResult = {
    for {
      _ <- checkNonEmpty(tx)
      _ <- checkOutputValue(tx)
      _ <- checkChainIndex(index, tx)
      _ <- checkTxDoubleSpending(tx)
      _ <- checkSpending(tx, trie)
    } yield ()
  }

  private[validation] def checkNonEmpty(tx: Transaction): TxValidationResult = {
    if (tx.unsigned.inputs.isEmpty) {
      invalidTx(EmptyInputs)
    } else if (tx.unsigned.outputs.isEmpty) {
      invalidTx(EmptyOutputs)
    } else {
      validTx
    }
  }

  private[validation] def checkOutputValue(tx: Transaction): TxValidationResult = {
    if (!tx.unsigned.outputs.forall(_.value >= 0)) {
      invalidTx(NegativeOutputValue)
    } else if (!(tx.unsigned.outputs.sumBy(_.value) < ALF.MaxALFValue)) {
      invalidTx(OutputValueOverFlow)
    } else {
      validTx
    }
  }

  private[validation] def checkChainIndex(index: ChainIndex, tx: Transaction)(
      implicit config: GroupConfig): TxValidationResult = {
    val fromOk = tx.unsigned.inputs.forall(_.fromGroup == index.from)
    val toOk = tx.unsigned.outputs.forall { output =>
      output.toGroup == index.from || output.toGroup == index.to
    }
    val existed = tx.unsigned.outputs.exists(_.toGroup == index.to)
    if (fromOk && toOk && existed) validTx else invalidTx(InvalidChainIndex)
  }

  private[validation] def checkBlockDoubleSpending(block: Block): TxValidationResult = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputPoint]
    block.transactions.init.foreachE { tx =>
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input)) invalidTx(DoubleSpent)
        else {
          utxoUsed += input
          validTx
        }
      }
    }
  }

  private[validation] def checkTxDoubleSpending(tx: Transaction): TxValidationResult = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputPoint]
    tx.unsigned.inputs.foreachE { input =>
      if (utxoUsed.contains(input)) invalidTx(DoubleSpent)
      else {
        utxoUsed += input
        validTx
      }
    }
  }

  private[validation] def checkSpending(tx: Transaction, trie: MerklePatriciaTrie)(
      implicit config: ScriptConfig): TxValidationResult = {
    val query = tx.unsigned.inputs.mapE { input =>
      trie.get[TxOutputPoint, TxOutput](input)
    }
    query match {
      case Right(preOutputs)                 => checkSpending(tx, preOutputs)
      case Left(IOError.RocksDB.keyNotFound) => invalidTx(NonExistInput)
      case Left(error)                       => Left(Left(error))
    }
  }

  private[validation] def checkSpending(tx: Transaction, preOutputs: AVector[TxOutput])(
      implicit config: ScriptConfig): TxValidationResult = {
    for {
      _ <- checkBalance(tx, preOutputs)
      _ <- checkWitnesses(tx, preOutputs)
    } yield ()
  }

  private[validation] def checkBalance(tx: Transaction,
                                       preOutputs: AVector[TxOutput]): TxValidationResult = {
    val inputSum  = preOutputs.sumBy(_.value)
    val outputSum = tx.unsigned.outputs.sumBy(_.value)
    if (outputSum <= inputSum) validTx else invalidTx(InvalidBalance)
  }

  private[validation] def checkWitnesses(tx: Transaction, preOutputs: AVector[TxOutput])(
      implicit config: ScriptConfig): TxValidationResult = {
    assume(tx.unsigned.inputs.length == preOutputs.length)
    EitherF.foreachTry(preOutputs.indices) { idx =>
      val witness = tx.witnesses(idx)
      val output  = preOutputs(idx)
      checkWitness(tx.hash, output.pubScript, witness)
    }
  }

  private[validation] def checkWitness(hash: Hash, pubScript: PubScript, witness: Witness)(
      implicit config: ScriptConfig): TxValidationResult = {
    Script.run(hash.bytes, pubScript, witness) match {
      case Left(error) => invalidTx(InvalidWitness(error))
      case Right(_)    => validTx
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
