package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.{IOError => ImportedIOError}
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

trait TransactionPool {
  import TransactionPool._

  implicit def config: PlatformConfig

  private val pool = AVector.tabulate(config.brokerInfo.groupNumPerBroker, config.groups) {
    case (_, _) => ConcurrentHashMap.empty[Keccak256, Transaction]
  }

  private def getIndexForInputs(inputs: AVector[TxOutputPoint]): Either[Error, GroupIndex] = {
    if (inputs.isEmpty) {
      Left(Error.invalidTransaction("Transaction has 0 input"))
    } else {
      val indexesEither = inputs
        .traverse(config.trie.get[TxOutputPoint, TxOutput])
        .map(_.map(output => GroupIndex.from(output.publicKey)))
      indexesEither match {
        case Left(error) => Left(Error.ioError(error))
        case Right(indexes) =>
          val index = indexes.head
          if (indexes.tail.forall(_ == index)) {
            Right(index)
          } else {
            Left(Error.invalidTransaction("Inputs has different group indexes"))
          }
      }
    }
  }

  private def getIndexForOutputs(outputs: AVector[TxOutput]): Either[Error, GroupIndex] = {
    if (outputs.isEmpty) {
      Left(Error.invalidTransaction("Transaction has 0 output"))
    } else {
      val indexes = outputs.map(output => GroupIndex.from(output.publicKey))
      val index   = indexes.head
      if (indexes.tail.forall(_ == index)) {
        Right(index)
      } else {
        Left(Error.invalidTransaction("Outputs has different group indexes"))
      }
    }
  }

  private def getIndex(unsigned: UnsignedTransaction): Either[Error, ChainIndex] = {
    for {
      from <- getIndexForInputs(unsigned.inputs)
      to   <- getIndexForOutputs(unsigned.outputs)
    } yield ChainIndex(from, to)
  }

  private def withTransaction[T](transaction: Transaction)(f: ChainIndex => T): Either[Error, T] = {
    getIndex(transaction.unsigned).flatMap { index =>
      if (config.brokerInfo.contains(index.from)) {
        Right(f(index))
      } else {
        Left(Error.invalidIndex(index, config.brokerInfo))
      }
    }
  }

  def addTxIntoPool(transaction: Transaction): Either[Error, Unit] = {
    withTransaction(transaction) { index =>
      pool(index.from.value)(index.to.value).add(transaction.hash, transaction)
    }
  }

  def removeTxFromPool(transaction: Transaction): Either[Error, Unit] = {
    withTransaction(transaction) { index =>
      pool(index.from.value)(index.to.value).removeIfExist(transaction.hash)
    }
  }

  def collectTransactions(chainIndex: ChainIndex): AVector[Transaction] = {
    AVector.unsafe(pool(chainIndex.from.value)(chainIndex.to.value).values.toArray)
  }
}
