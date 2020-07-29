package org.alephium.appserver

import scala.concurrent._

import akka.util.Timeout
import io.circe.syntax._

import org.alephium.appserver.ApiModel._
import org.alephium.appserver.RPCServerAbstract.{FutureTry, Try}
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.{ChainIndex, Transaction, UnsignedTransaction}
import org.alephium.protocol.vm._
import org.alephium.rpc.CirceUtils
import org.alephium.rpc.model.JsonRPC._
import org.alephium.serde.deserialize
import org.alephium.util.{ActorRefT, AVector, Hex, U64}

object ServerUtils {
  def getBlockflow(blockFlow: BlockFlow, fetchRequest: FetchRequest)(
      implicit cfg: ConsensusConfig): Try[FetchResponse] = {
    val entriesEither = for {
      headers <- blockFlow.getHeightedBlockHeaders(fetchRequest.fromTs, fetchRequest.toTs)
    } yield headers.map { case (header, height) => BlockEntry.from(header, height) }

    entriesEither match {
      case Right(entries) => Right(FetchResponse(entries.toArray.toIndexedSeq))
      case Left(_)        => failedInIO[FetchResponse]
    }
  }

  def getBalance(blockFlow: BlockFlow, balanceRequest: GetBalance): Try[Balance] =
    for {
      _ <- checkGroup(blockFlow, balanceRequest.address)
      balance <- blockFlow
        .getBalance(balanceRequest.address)
        .map(Balance(_))
        .left
        .flatMap(_ => failedInIO)
    } yield balance

  def getGroup(blockFlow: BlockFlow, query: GetGroup): Try[Group] = {
    Right(Group(query.address.groupIndex(blockFlow.config).value))
  }

  def createTransaction(blockFlow: BlockFlow,
                        query: CreateTransaction): Try[CreateTransactionResult] = {
    val resultEither = for {
      _ <- checkGroup(blockFlow, query.fromKey)
      unsignedTx <- prepareUnsignedTransaction(blockFlow,
                                               query.fromKey,
                                               query.toAddress,
                                               query.value)
    } yield {
      CreateTransactionResult.from(unsignedTx)
    }
    resultEither match {
      case Right(result) => Right(result)
      case Left(error)   => Left(error)
    }
  }

  def sendTransaction(txHandler: ActorRefT[TxHandler.Command], query: SendTransaction)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    val txEither = for {
      txByteString <- Hex.from(query.tx).toRight(Response.failed(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
        Response.failed(serdeError.getMessage))
    } yield {
      Transaction.from(unsignedTx, AVector.fill(unsignedTx.inputs.length)(query.signature))
    }
    txEither match {
      case Right(tx)   => publishTx(txHandler, tx)
      case Left(error) => Future.successful(Left(error))
    }
  }

  def getBlock(blockFlow: BlockFlow, query: GetBlock)(
      implicit cfg: ConsensusConfig): Try[BlockEntry] =
    for {
      _ <- checkChainIndex(blockFlow, query.hash)
      block <- blockFlow
        .getBlock(query.hash)
        .left
        .map(_ => Response.failed(s"Fail fetching block with header ${query.hash.toHexString}"))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(_ => Response.failed("Failed in IO"))
    } yield BlockEntry.from(block, height)

  def getHashesAtHeight(blockFlow: BlockFlow,
                        chainIndex: ChainIndex,
                        query: GetHashesAtHeight): Try[HashesAtHeight] =
    for {
      hashes <- blockFlow
        .getHashes(chainIndex, query.height)
        .left
        .map(_ => Response.failed("Failed in IO"))
    } yield HashesAtHeight(hashes.map(_.toHexString).toArray.toIndexedSeq)

  def getChainInfo(blockFlow: BlockFlow, chainIndex: ChainIndex): Try[ChainInfo] =
    for {
      maxHeight <- blockFlow
        .getMaxHeight(chainIndex)
        .left
        .map(_ => Response.failed("Failed in IO"))
    } yield ChainInfo(maxHeight)

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: Transaction)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    val message = TxHandler.AddTx(tx, DataOrigin.Local)
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case _: TxHandler.AddSucceeded =>
        Right(TxResult(tx.hash.toHexString, tx.fromGroup.value, tx.toGroup.value))
      case _: TxHandler.AddFailed =>
        Left(Response.failed("Failed in adding transaction"))
    }
  }

  def prepareTransaction(blockFlow: BlockFlow,
                         fromKey: ED25519PublicKey,
                         toKey: ED25519PublicKey,
                         value: U64,
                         fromPrivateKey: ED25519PrivateKey): Try[Transaction] = {
    val fromLockupScript = LockupScript.p2pkh(fromKey)
    val fromUnlockScript = UnlockScript.p2pkh(toKey)
    val toLockupScript   = LockupScript.p2pkh(toKey)
    blockFlow.prepareTx(fromLockupScript, fromUnlockScript, toLockupScript, value, fromPrivateKey) match {
      case Right(Some(transaction)) => Right(transaction)
      case Right(None)              => Left(Response.failed("Not enough balance"))
      case Left(_)                  => failedInIO
    }
  }

  def prepareUnsignedTransaction(blockFlow: BlockFlow,
                                 fromKey: ED25519PublicKey,
                                 toLockupScript: LockupScript,
                                 value: U64): Try[UnsignedTransaction] = {
    val fromLockupScript = LockupScript.p2pkh(fromKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromKey)
    blockFlow.prepareUnsignedTx(fromLockupScript, fromUnlockScript, toLockupScript, value) match {
      case Right(Some(unsignedTransaction)) => Right(unsignedTransaction)
      case Right(None)                      => Left(Response.failed("Not enough balance"))
      case Left(_)                          => failedInIO
    }
  }

  def checkGroup(blockFlow: BlockFlow, publicKey: ED25519PublicKey): Try[Unit] = {
    checkGroup(blockFlow, LockupScript.p2pkh(publicKey))
  }

  def checkGroup(blockFlow: BlockFlow, address: Address): Try[Unit] = {
    val groupIndex = address.groupIndex(blockFlow.config)
    if (blockFlow.config.brokerInfo.contains(groupIndex)) Right(())
    else {
      val addressStr = CirceUtils.print(address.asJson)
      Left(Response.failed(s"Address $addressStr belongs to other groups"))
    }
  }

  def checkChainIndex(blockFlow: BlockFlow, hash: Hash)(
      implicit groupConfig: GroupConfig): Try[Unit] = {
    val chainIndex = ChainIndex.from(hash)
    if (blockFlow.config.brokerInfo.contains(chainIndex.from) || blockFlow.config.brokerInfo
          .contains(chainIndex.to)) Right(())
    else Left(Response.failed(s"${hash.toHexString} belongs to other groups"))
  }

  def execute(f: => Unit)(implicit ec: ExecutionContext): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  private def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))
}
