package org.alephium.flow.core.validation

import org.alephium.crypto.Keccak256
import org.alephium.flow.core._
import org.alephium.flow.io.IOResult
import org.alephium.flow.platform.PlatformProfile
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.script.{PubScript, Script, Witness}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, EitherF, Forest, TimeStamp}

abstract class Validation[T <: FlowData, S <: ValidationStatus]() {
  def validate(data: T, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): IOResult[S]

  def validateUntilDependencies(data: T, flow: BlockFlow, isSyncing: Boolean): IOResult[S]

  def validateAfterDependencies(data: T, flow: BlockFlow)(
      implicit config: PlatformProfile): IOResult[S]
}

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
      implicit config: PlatformProfile): HeaderValidationResult = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
    } yield ()
  }

  private[validation] def validateHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): HeaderValidationResult = {
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
      implicit config: PlatformProfile): BlockValidationResult = {
    for {
      _ <- validateHeaderAfterDependencies(block.header, flow)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): BlockValidationResult = {
    for {
      _ <- validateHeader(block.header, flow, isSyncing)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlockAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): BlockValidationResult = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      _ <- checkTransactions(block, flow)
    } yield ()
  }

  /*
   * The following functions are all the check functions behind validations
   */

  private[validation] def checkGroup(block: Block)(
      implicit config: PlatformProfile): BlockValidationResult = {
    if (block.chainIndex.relateTo(config.brokerInfo)) validBlock
    else invalidBlock(InvalidGroup)
  }

  private[validation] def checkTimeStamp(header: BlockHeader,
                                         isSyncing: Boolean): HeaderValidationResult = {
    val now      = TimeStamp.now()
    val headerTs = header.timestamp

    val ok1 = headerTs < now.plusHours(1)
    val ok2 = isSyncing || (headerTs > now.plusHours(-1))
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
    val coinbase = block.transactions.head // Note: validateNonEmptyTransactions first pls!
    val raw      = coinbase.raw
    if (raw.inputs.length == 0 && raw.outputs.length == 1 && coinbase.witnesses.isEmpty)
      validBlock
    else invalidBlock(InvalidCoinbase)
  }

  // TODO: use Merkle hash for transactions
  private[validation] def checkMerkleRoot(block: Block): BlockValidationResult = {
    if (block.header.txsHash == Keccak256.hash(block.transactions)) validBlock
    else invalidBlock(InvalidMerkleRoot)
  }

  private[validation] def checkTransactions(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): TxValidationResult = {
    val index      = block.chainIndex
    val brokerInfo = config.brokerInfo
    assert(index.relateTo(brokerInfo))

    if (brokerInfo.contains(index.from)) {
      val trie = flow.getTrie(block)
      for {
        _ <- checkSpending(block, trie)
        _ <- checkAllScripts(block, trie)
      } yield ()
    } else {
      validTx
    }
  }

  private[validation] def checkSpending(block: Block,
                                        trie: MerklePatriciaTrie): TxValidationResult = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputPoint]
    EitherF.foreach(block.transactions.tail.toIterable) { tx =>
      EitherF.foreach(tx.raw.inputs.toIterable) { input =>
        if (utxoUsed.contains(input)) invalidTx(DoubleSpent)
        else {
          utxoUsed += input
          trie.getOpt[TxOutputPoint, TxOutput](input) match {
            case Left(error)        => Left(Left(error))
            case Right(txOutputOpt) => if (txOutputOpt.isEmpty) invalidTx(InvalidCoin) else validTx
          }
        }
      }
    }
  }

  private[validation] def checkAllScripts(block: Block,
                                          trie: MerklePatriciaTrie): TxValidationResult = {
    val transactions = block.transactions
    EitherF.foreach(transactions.indices.tail)(idx => checkTxScripts(transactions(idx), trie))
  }

  private[validation] def checkTxScripts(tx: Transaction,
                                         trie: MerklePatriciaTrie): TxValidationResult = {
    val inputs    = tx.raw.inputs
    val witnesses = tx.witnesses
    if (inputs.length != witnesses.length) {
      invalidTx(InvalidWitnessLength)
    } else {
      val rawHash = Keccak256.hash(serialize(tx.raw))
      EitherF.foreach(inputs.indices) { idx =>
        val input   = inputs(idx)
        val witness = witnesses(idx)
        trie.get[TxOutputPoint, TxOutput](input) match {
          case Left(error)      => Left(Left(error))
          case Right(preOutput) => checkWitness(rawHash, preOutput.pubScript, witness)
        }
      }
    }
  }

  private[validation] def checkWitness(hash: Keccak256,
                                       pubScript: PubScript,
                                       witness: Witness): TxValidationResult = {
    Script.run(hash.bytes, pubScript, witness) match {
      case Left(error) => invalidTx(InvalidWitness(error))
      case Right(_)    => validTx
    }
  }

  /*
   * The following functions are for other type of validation
   */

  def validateFlowDAG[T <: FlowData](datas: AVector[T])(
      implicit config: GroupConfig): Option[AVector[Forest[Keccak256, T]]] = {
    val splits = datas.splitBy(_.chainIndex)
    val builds = splits.map(ds => Forest.tryBuild[Keccak256, T](ds, _.hash, _.parentHash))
    if (builds.forall(_.nonEmpty)) Some(builds.map(_.get)) else None
  }

  def validateMined[T <: FlowData](data: T, index: ChainIndex)(
      implicit config: GroupConfig): Boolean = {
    data.chainIndex == index && checkWorkAmount(data).isRight
  }
}
