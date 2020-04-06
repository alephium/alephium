package org.alephium.flow.core

import org.alephium.flow.io.{IOError => ImportedIOError}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model._
import org.alephium.util.{AVector, ConcurrentHashMap}

object TransactionPool {
  sealed trait Error
  final case class InvalidTransaction(msg: String) extends Error
  final case class InvalidIndex(msg: String)       extends Error
  final case class IOError(error: ImportedIOError) extends Error

  object Error {
    def invalidTransaction(msg: String): InvalidTransaction = InvalidTransaction(msg)
    def invalidIndex(index: ChainIndex, brokerInfo: BrokerInfo): InvalidIndex =
      InvalidIndex(s"Got $index, but broker is $brokerInfo")
    def ioError(error: ImportedIOError): IOError = IOError(error)
  }
}

trait TransactionPool { self: BlockFlowState =>
  import TransactionPool._

  implicit def config: PlatformConfig

  private val pool = AVector.tabulate(config.brokerInfo.groupNumPerBroker, config.groups) {
    (_, _) =>
      ConcurrentHashMap.empty[Hash, Transaction]
  }

  def validateIndex(input: TxOutputPoint,
                    trie: MerklePatriciaTrie,
                    from: GroupIndex): Either[Error, Unit] = {
    val errorRes   = Left[Error, Unit](Error.invalidTransaction("Input has different group index"))
    val groupCache = getGroupCache(from)
    trie.getOpt[TxOutputPoint, TxOutput](input) match {
      case Left(e) => Left(Error.ioError(e))
      case Right(Some(output)) =>
        if (GroupIndex.from(output.pubScript) == from &&
            (!groupCache.isUtxoSpentIncache(input))) Right(())
        else errorRes
      case Right(None) =>
        if (groupCache.isUtxoAvailableIncache(input) &&
            !groupCache.isUtxoSpentIncache(input)) Right(())
        else errorRes
    }
  }

  def validateIndex(output: TxOutput, to: GroupIndex): Either[Error, Unit] = {
    if (GroupIndex.from(output.pubScript) == to) {
      Right(())
    } else {
      Left(Error.invalidTransaction("Output has differnet group index"))
    }
  }

  def validateIndex(transaction: Transaction, chainIndex: ChainIndex): Either[Error, Unit] = {
    val trie = getBestTrie(chainIndex.from)
    for {
      _ <- transaction.unsigned.inputs.mapE(validateIndex(_, trie, chainIndex.from))
      _ <- transaction.unsigned.outputs.mapE(validateIndex(_, chainIndex.to))
    } yield ()
  }

  private def getPool(chainIndex: ChainIndex) = {
    assert(brokerInfo.contains(chainIndex.from))
    val fromShift = chainIndex.from.value - brokerInfo.groupFrom
    pool(fromShift)(chainIndex.to.value)
  }

  def addTxIntoPool(transaction: Transaction, chainIndex: ChainIndex): Either[Error, Unit] = {
    validateIndex(transaction, chainIndex).map { _ =>
      getPool(chainIndex).add(transaction.hash, transaction)
    }
  }

  def removeTxFromPool(transaction: Transaction, chainIndex: ChainIndex): Unit = {
    getPool(chainIndex).removeIfExist(transaction.hash)
  }

  // TODO: consider complete block template view
  def collectTransactions(chainIndex: ChainIndex): AVector[Transaction] = {
    AVector.unsafe(getPool(chainIndex).values.toArray)
  }
}
