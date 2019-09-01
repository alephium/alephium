package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.{IOError => ImportedIOError}
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.model._
import org.alephium.util.{AVector, ConcurrentHashMap}

object TransactionPool {
  sealed trait Error
  case class InvalidTransaction(msg: String) extends Error
  case class InvalidIndex(msg: String)       extends Error
  case class IOError(msg: String)            extends Error

  object Error {
    def invalidTransaction(msg: String): InvalidTransaction = InvalidTransaction(msg)
    def invalidIndex(index: ChainIndex, brokerInfo: BrokerInfo): InvalidIndex =
      InvalidIndex(s"Got $index, but broker is $brokerInfo")
    def ioError(error: ImportedIOError): IOError = IOError(error.getMessage)
  }
}

trait TransactionPool { self: BlockFlowState =>
  import TransactionPool._

  implicit def config: PlatformConfig

  private val pool = AVector.tabulate(config.brokerInfo.groupNumPerBroker, config.groups) {
    case (_, _) => ConcurrentHashMap.empty[Keccak256, Transaction]
  }

  def validateIndex(input: TxOutputPoint,
                    trie: MerklePatriciaTrie,
                    from: GroupIndex): Either[Error, Unit] = {
    val errorRes   = Left[Error, Unit](Error.invalidTransaction("Input has different group index"))
    val groupCache = getGroupCache(from)
    trie.getOpt[TxOutputPoint, TxOutput](input) match {
      case Left(e) => Left(Error.ioError(e))
      case Right(Some(output)) =>
        if (GroupIndex.from(output.mainKey) == from &&
            (!groupCache.isUtxoSpentIncache(input))) Right(())
        else errorRes
      case Right(None) =>
        if (groupCache.isUtxoAvailableIncache(input) &&
            !groupCache.isUtxoSpentIncache(input)) Right(())
        else errorRes
    }
  }

  def validateIndex(output: TxOutput, to: GroupIndex): Either[Error, Unit] = {
    if (GroupIndex.from(output.mainKey) == to) {
      Right(())
    } else {
      Left(Error.invalidTransaction("Output has differnet group index"))
    }
  }

  def validateIndex(transaction: Transaction, chainIndex: ChainIndex): Either[Error, Unit] = {
    val trie = getBestTrie(chainIndex.from)
    for {
      _ <- transaction.unsigned.inputs.traverse(validateIndex(_, trie, chainIndex.from))
      _ <- transaction.unsigned.outputs.traverse(validateIndex(_, chainIndex.to))
    } yield ()
  }

  def addTxIntoPool(transaction: Transaction, chainIndex: ChainIndex): Either[Error, Unit] = {
    validateIndex(transaction, chainIndex).map { _ =>
      pool(chainIndex.from.value)(chainIndex.to.value).add(transaction.hash, transaction)
    }
  }

  def removeTxFromPool(transaction: Transaction, chainIndex: ChainIndex): Unit = {
    pool(chainIndex.from.value)(chainIndex.to.value).removeIfExist(transaction.hash)
  }

  // TODO: consider complete block template view
  def collectTransactions(chainIndex: ChainIndex): AVector[Transaction] = {
    AVector.unsafe(pool(chainIndex.from.value)(chainIndex.to.value).values.toArray)
  }
}
