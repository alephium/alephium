package org.alephium.flow.core.validation

import org.alephium.crypto.{ED25519Signature, Keccak256}
import org.alephium.flow.core._
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.TimeStamp

object Validation {
  private[validation] type HeaderValidationResult =
    Either[Either[IOError, InvalidHeaderStatus], Unit]
  private[validation] type BlockValidationResult = Either[Either[IOError, InvalidBlockStatus], Unit]

  private def invalidHeader(status: InvalidHeaderStatus): HeaderValidationResult =
    Left(Right(status))
  private def invalidBlock(status: InvalidBlockStatus): BlockValidationResult = Left(Right(status))
  private val validHeader: HeaderValidationResult                             = Right(())
  private val validBlock: BlockValidationResult                               = Right(())

  private def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] = x match {
    case Left(Left(error)) => Left(error)
    case Left(Right(t))    => Right(t)
    case Right(())         => Right(default)
  }

  def validate(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile
  ): IOResult[HeaderStatus] = {
    convert(validateHeader(header, flow, isSyncing), ValidHeader)
  }

  def validate(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): IOResult[BlockStatus] = {
    convert(validateBlock(block, flow, isSyncing), ValidBlock)
  }

  def validatePostHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): IOResult[BlockStatus] = {
    convert(validateBlockPostHeader(block, flow), ValidBlock)
  }

  private def validateHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): HeaderValidationResult = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkTimeStamp(header, isSyncing)
      _ <- checkWorkAmount(header)
      _ <- checkParent(header, headerChain)
      _ <- checkDependencies(header, flow)
      _ <- checkWorkTarget(header, headerChain)
    } yield ()
  }

  private def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): BlockValidationResult = {
    for {
      _ <- validateHeader(block.header, flow, isSyncing)
      _ <- validateBlockPostHeader(block, flow)
    } yield ()
  }

  private def validateBlockPostHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): BlockValidationResult = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      //      _ <- checkTxsSignature(block, flow) // TODO: design & implement this
      _ <- checkSpending(block, flow)
    } yield ()
  }

  def validateMined(block: Block, index: ChainIndex)(implicit config: GroupConfig): Boolean = {
    validateMined(block.header, index)
  }

  def validateMined(header: BlockHeader, index: ChainIndex)(
      implicit config: GroupConfig): Boolean = {
    header.chainIndex == index && checkWorkAmount(header).isRight
  }

  /*
   * The following functions are all the check functions behind validations
   */

  def checkGroup(block: Block)(implicit config: PlatformProfile): BlockValidationResult = {
    if (block.chainIndex.relateTo(config.brokerInfo)) validBlock
    else invalidBlock(InvalidGroup)
  }

  def checkTimeStamp(header: BlockHeader, isSyncing: Boolean): HeaderValidationResult = {
    val now      = TimeStamp.now()
    val headerTs = header.timestamp

    val ok1 = headerTs < now.plusHours(1)
    val ok2 = isSyncing || (headerTs > now.plusHours(-1))
    if (ok1 && ok2) validHeader else invalidHeader(InvalidTimeStamp)
  }

  def checkWorkAmount(header: BlockHeader): HeaderValidationResult = {
    val current = BigInt(1, header.hash.bytes.toArray)
    assert(current >= 0)
    if (current <= header.target) validHeader else invalidHeader(InvalidWorkAmount)
  }

  def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult = {
    headerChain.getHashTarget(header.parentHash) match {
      case Left(error) => Left(Left(error))
      case Right(target) =>
        if (target == header.target) validHeader else invalidHeader(InvalidWorkTarget)
    }
  }

  def checkParent(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult = {
    if (headerChain.contains(header.parentHash)) validHeader else invalidHeader(MissingParent)
  }

  def checkDependencies(header: BlockHeader, flow: BlockFlow): HeaderValidationResult = {
    val missings = header.blockDeps.filterNot(flow.contains)
    if (missings.isEmpty) validHeader else invalidHeader(MissingDeps(missings))
  }

  def checkNonEmptyTransactions(block: Block): BlockValidationResult = {
    if (block.transactions.nonEmpty) validBlock else invalidBlock(EmptyTransactionList)
  }

  def checkCoinbase(block: Block): BlockValidationResult = {
    val coinbase = block.transactions.head // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (unsigned.inputs.length == 0 && unsigned.outputs.length == 1 && coinbase.signature == ED25519Signature.zero)
      validBlock
    else invalidBlock(InvalidCoinbase)
  }

  // TODO: use Merkle hash for transactions
  def checkMerkleRoot(block: Block): BlockValidationResult = {
    if (block.header.txsHash == Keccak256.hash(block.transactions)) validBlock
    else invalidBlock(InvalidMerkleRoot)
  }

  // TODO: refine transaction validation and test properly
  def checkSpending(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): BlockValidationResult = {
    val index      = block.chainIndex
    val brokerInfo = config.brokerInfo
    assert(index.relateTo(brokerInfo))

    val boolFrom = brokerInfo.contains(index.from)
    if (boolFrom) {
      val trie = flow.getTrie(block)
      checkSpending(block, trie)
    } else {
      validBlock
    }
  }

  private def checkSpending(block: Block, trie: MerklePatriciaTrie): BlockValidationResult = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputPoint]
    block.transactions.foreach { tx =>
      tx.unsigned.inputs.foreach { txOutputPoint =>
        // scalastyle:off return
        if (utxoUsed.contains(txOutputPoint)) return invalidBlock(DoubleSpent)
        else {
          utxoUsed += txOutputPoint
          trie.getOpt[TxOutputPoint, TxOutput](txOutputPoint) match {
            case Left(error)        => return Left(Left(error))
            case Right(txOutputOpt) => if (txOutputOpt.isEmpty) return invalidBlock(InvalidCoin)
          }
        }
        // scalastyle:on return
      }
    }
    validBlock
  }
}
